(ns bbcecko.app
  "bbcecko: a small, friendly C++ IDE for students.

   One atom holds the whole app. The view is a pure function of it; every
   click is data ([:tree/click path]) sent to `handle!`. Work that blocks --
   compiling, the running program, the folder watcher -- happens on its own
   thread and only ever `swap!`s the atom, which is safe from anywhere.
   Anything that touches GTK (reading a buffer, focusing a widget) runs in a
   handler, which is already on the GTK thread, or through `ui/later!`."
  (:require [babashka.fs :as fs]
            [bbcecko.compiler :as cc]
            [bbcecko.files :as f]
            [bbcecko.gtk :as gs]
            [bbcecko.i18n :as i18n :refer [t]]
            [bbcecko.platform :as platform]
            [bbcecko.runner :as runner]
            [bbcecko.themes :as th]
            [bbcecko.widgets :as bw]
            [clojure.string :as str]
            [gtkiccup.core :as ui]
            [gtkiccup.ffi :as g]
            [gtkiccup.ratom :as r]
            [gtkiccup.refs :as refs]))

;; ---------------------------------------------------------------------------
;; state
;; ---------------------------------------------------------------------------

(def initial-state
  {:root nil
   :expanded #{}
   :listings {}             ; dir -> [{:name :path :dir?}]
   :selected nil
   :tabs []
   :active nil
   :docs {}                 ; path -> {:text initial-text :dirty? bool}
   :cursor [1 1]
   :theme :midnight
   :font-size 15
   :compiler {:status :checking}
   :run {:status :idle}
   :console {:segments [] :epoch 0}
   :diagnostics []
   :bottom :output
   :goto nil
   :creating nil            ; {:kind :file|:dir :dir path :name ""}
   :confirm-delete nil
   :toast nil
   :stdin ""})

(defonce state (r/atom initial-state))
(defonce process (atom nil))
(defonce css-provider (atom nil))
(defonce config (atom {}))

(declare handle! open-file! set-root!)

;; ---------------------------------------------------------------------------
;; small helpers
;; ---------------------------------------------------------------------------

(defn- cls
  "A class list without the nils, so (when x \"y\") reads naturally."
  [& xs]
  (vec (remove nil? (flatten xs))))

(defn- escape [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- fmt-secs [ms]
  (let [s (format "%.2f" (/ ms 1000.0))]
    (if (= :cs i18n/*lang*) (str/replace s "." ",") s)))

(defn toast!
  ([text] (toast! text false))
  ([text error?]
   (let [id (str (random-uuid))]
     (swap! state assoc :toast {:text text :error? error? :id id})
     (future (Thread/sleep 2600)
             (swap! state #(if (= id (get-in % [:toast :id])) (assoc % :toast nil) %))))))

(def ^:private console-keep
  "How many characters of output the state keeps. The console widget keeps
   its own scrollback; this only bounds memory."
  120000)

(defn- trim-console [{:keys [segments] :as c}]
  (loop [segs segments
         chars (reduce + (map (comp count second) segments))
         dropped 0]
    (if (and (> chars console-keep) (> (count segs) 1))
      (recur (subvec segs 1) (- chars (count (second (first segs)))) (inc dropped))
      (-> c (assoc :segments segs) (update :base (fnil + 0) dropped)))))

(defn- console! [& segs]
  (swap! state update :console #(trim-console (update % :segments into segs))))

(defn- clear-console! []
  (swap! state update :console #(-> % (assoc :segments [] :base 0) (update :epoch inc))))

(defn- focus-ref! [name]
  (ui/later! #(when-let [w (refs/resolve name)] (gs/widget-grab-focus w))))

;; ---------------------------------------------------------------------------
;; look
;; ---------------------------------------------------------------------------

(defn apply-theme!
  "Reloads the one stylesheet for the current theme and font size. GTK thread."
  []
  (let [{:keys [theme font-size]} @state]
    (when-let [p @css-provider]
      (g/css-provider-load-from-string p (th/css theme font-size)))
    (gs/prefer-dark! (:dark? (th/theme theme)))))

;; ---------------------------------------------------------------------------
;; settings
;; ---------------------------------------------------------------------------

(defn- persist!
  "Writes what should survive a restart. Called from a watch, so only when one
   of these values really changed."
  [{:keys [root theme font-size tabs active]}]
  (let [cfg (cond-> (assoc @config :theme theme :font-size font-size)
              root (assoc :root root)
              root (assoc-in [:roots root] {:tabs tabs :active active}))]
    (when (not= cfg @config)
      (reset! config cfg)
      (future (f/save-config! cfg)))))

(defn- watch-persist! []
  (add-watch state ::persist
             (fn [_ _ old new]
               (let [ks [:root :theme :font-size :tabs :active]]
                 (when (not= (select-keys old ks) (select-keys new ks))
                   (persist! new))))))

;; ---------------------------------------------------------------------------
;; explorer
;; ---------------------------------------------------------------------------

(defn- load-listing! [dir]
  (swap! state assoc-in [:listings dir] (f/list-dir dir)))

(defn- refresh-listings!
  "Re-lists the root and every open folder; touches the atom only when
   something on disk really changed."
  []
  (let [{:keys [root expanded listings]} @state]
    (when root
      (let [dirs  (cons root (filter #(str/starts-with? % root) expanded))
            fresh (into {} (for [d dirs :when (fs/directory? d)] [d (f/list-dir d)]))]
        (when (not= fresh (select-keys listings (keys fresh)))
          (swap! state update :listings merge fresh))))))

(defonce ^:private watcher (atom nil))

(defn- start-watcher!
  "Polls the open folders every 1.5 s. A student's folder is small, and
   polling needs nothing that differs between platforms."
  []
  (when-not @watcher
    (reset! watcher
            (future
              (loop []
                (Thread/sleep 1500)
                (try (refresh-listings!) (catch Throwable _ nil))
                (recur))))))

(defn- tree-rows [listings expanded dir depth]
  (mapcat (fn [{:keys [path dir?] :as e}]
            (cons (assoc e :depth depth)
                  (when (and dir? (expanded path))
                    (tree-rows listings expanded path (inc depth)))))
          (get listings dir)))

(defn- target-dir
  "Where a new file goes: the selected folder, or the folder of the selected
   file, or the root."
  [{:keys [root selected]}]
  (cond (nil? selected) root
        (fs/directory? selected) selected
        :else (str (fs/parent selected))))

;; ---------------------------------------------------------------------------
;; documents
;; ---------------------------------------------------------------------------

(defn- save-doc!
  "Writes one open file from its buffer. GTK thread."
  [path]
  (when-let [text (bw/buffer-text path)]
    (try
      (f/write-text! path text)
      (bw/mark-saved! path)
      (swap! state assoc-in [:docs path :dirty?] false)
      true
      (catch Exception e
        (toast! (t :toast/error (ex-message e)) true)
        false))))

(defn- save-all! []
  (doseq [[p {:keys [dirty?]}] (:docs @state) :when dirty?]
    (save-doc! p)))

(defn open-file!
  "Opens `path` in a tab (or switches to it)."
  [path]
  (let [st @state]
    (if (get-in st [:docs path])
      (swap! state assoc :active path :selected path)
      (let [{:keys [text error]} (f/read-text path)]
        (if error
          (toast! (t (if (= :too-big error) :editor/too-big :editor/unsupported)) true)
          (swap! state #(-> %
                            (assoc-in [:docs path] {:text text :dirty? false})
                            (update :tabs (fn [ts] (if (some #{path} ts) ts (conj ts path))))
                            (assoc :active path :selected path))))))))

(defn- close-tab! [path]
  (when (get-in @state [:docs path :dirty?]) (save-doc! path))
  (bw/drop-buffer! path)
  (swap! state (fn [{:keys [tabs active] :as st}]
                 (let [i     (.indexOf ^java.util.List tabs path)
                       tabs' (vec (remove #{path} tabs))
                       next  (when (seq tabs') (nth tabs' (min (max 0 i) (dec (count tabs')))))]
                   (-> st
                       (assoc :tabs tabs')
                       (update :docs dissoc path)
                       (assoc :active (if (= active path) next active)))))))

(defn- editor-event
  "What the editor widget reports. Runs on the GTK thread, sometimes inside a
   render, so it only writes when the value really changed."
  [[kind & args]]
  (case kind
    :modified (let [[path dirty?] args]
                (when (and (get-in @state [:docs path])
                           (not= dirty? (get-in @state [:docs path :dirty?])))
                  (swap! state assoc-in [:docs path :dirty?] dirty?)))
    :cursor   (let [pos (vec args)]
                (when (not= pos (:cursor @state))
                  (swap! state assoc :cursor pos)))
    nil))

(defn set-root!
  "Makes `dir` the folder the explorer shows, restoring the tabs it had."
  [dir]
  (let [dir (str (fs/canonicalize dir))]
    (save-all!)
    (doseq [p (:tabs @state)] (bw/drop-buffer! p))
    (let [{:keys [tabs active]} (get-in @config [:roots dir])
          tabs (vec (filter fs/regular-file? tabs))]
      (swap! state merge {:root dir :expanded #{} :listings {} :selected nil
                          :tabs [] :active nil :docs {} :creating nil
                          :confirm-delete nil :diagnostics []})
      (load-listing! dir)
      ;; reopen what was open last time, folders included
      (doseq [p tabs]
        (doseq [d (take-while #(and % (not= (str %) dir) (str/starts-with? (str %) dir))
                              (iterate fs/parent (fs/parent p)))]
          (swap! state update :expanded conj (str d))
          (load-listing! (str d)))
        (open-file! p))
      (when (and active (some #{active} tabs))
        (swap! state assoc :active active :selected active)))))

(defn- choose-root! []
  (gs/choose-folder! (:window @ui/current) (t :root/dialog-title)
                     (or (:root @state) (str (fs/home)))
                     (fn [dir] (set-root! dir))))

;; ---------------------------------------------------------------------------
;; compile and run
;; ---------------------------------------------------------------------------

(defn check-compiler! []
  (swap! state assoc :compiler {:status :checking})
  (future
    (let [c (cc/detect)]
      (swap! state assoc :compiler
             (cond-> c (= :missing (:status c)) (assoc :hint (cc/install-hint)))))))

(defn- busy? [st] (#{:compiling :running} (get-in st [:run :status])))

(defn- can-run? [{:keys [active compiler] :as st}]
  (and active (f/runnable? active) (= :ok (:status compiler)) (not (busy? st))))

(defn- with-snippets
  "Each diagnostic gets the line of code it points at, read from disk -- the
   file was saved just before compiling."
  [diags]
  (let [lines (memoize (fn [file] (try (str/split-lines (slurp file)) (catch Exception _ []))))]
    (mapv (fn [{:keys [file line] :as d}]
            (if (and file line)
              (assoc d :snippet (get (lines file) (dec line)))
              d))
          diags)))

(defn- goto! [path line col]
  (when path
    (open-file! path)
    (swap! state #(assoc % :goto {:path path :line line :col (or col 1)
                                  :n (inc (get-in % [:goto :n] 0))}))))

(defn- on-exit [{:keys [code stopped? ms error]}]
  (reset! process nil)
  (console! (cond
              stopped?        [:sys (str "\n■ " (t :run/stopped) "\n")]
              error           [:err (str "\n" (t :toast/error error) "\n")]
              (zero? code)    [:ok (str "\n✔ " (t :run/exit-ok (fmt-secs ms)) "\n")]
              (= code 139)    [:err (str "\n✖ " (t :run/signal-11) "\n")]
              (= code 136)    [:err (str "\n✖ " (t :run/signal-8) "\n")]
              (= code 134)    [:err (str "\n✖ " (t :run/signal-6) "\n")]
              (> code 128)    [:err (str "\n✖ " (t :run/signal (- code 128)) "\n")]
              :else           [:sys (str "\n■ " (t :run/exit code (fmt-secs ms)) "\n")]))
  (swap! state assoc :run {:status :done :code code}))

(defn- start-program! [exe src]
  (let [h (runner/start! exe (str (fs/parent src))
                         {:on-output (fn [segs] (apply console! segs))
                          :skipped-note (str "\n… " (t :run/skipped) " …\n")
                          :on-exit on-exit})]
    (reset! process h)
    (swap! state assoc :run {:status :running :file src} :stdin "")
    (focus-ref! "stdin")))

(defn run!
  "Save, compile, run. GTK thread (it reads the editor buffers)."
  []
  (let [st @state]
    (when (can-run? st)
      (save-all!)
      (let [src (:active st)
            compiler (:compiler st)]
        (clear-console!)
        (console! [:sys (str "▶ " (t :run/compiling (fs/file-name src)) "\n")])
        (swap! state assoc :run {:status :compiling :file src} :diagnostics [] :bottom :output)
        (future
          (try
            (let [{:keys [ok? out diagnostics]} (cc/compile! compiler (f/cache-dir) src)
                  diags  (with-snippets diagnostics)
                  errors (filter #(= :error (:severity %)) diags)
                  warns  (filter #(= :warning (:severity %)) diags)]
              (swap! state assoc :diagnostics diags)
              (if ok?
                (do (when (seq warns)
                      (console! [:sys (str (t :run/compiled-warnings (count warns)) "\n")]))
                    (console! [:sys "──────────────────────────────\n"])
                    (start-program! out src))
                (let [first-err (first (filter :line errors))]
                  (console! [:err (str "✖ " (t :run/failed (t :diag/count (count errors))) "\n")])
                  (swap! state assoc :run {:status :failed} :bottom :problems)
                  (when first-err
                    (goto! (:file first-err) (:line first-err) (:col first-err))))))
            (catch Throwable e
              (console! [:err (str (t :toast/error (ex-message e)) "\n")])
              (swap! state assoc :run {:status :failed}))))))))

(defn stop! []
  (when-let [h @process] (runner/stop! h)))

(defn- send-stdin! [text]
  (when-let [h @process]
    (console! [:input (str text "\n")])
    (runner/send! h text)
    (swap! state assoc :stdin "")))

;; ---------------------------------------------------------------------------
;; file operations
;; ---------------------------------------------------------------------------

(defn- start-creating! [kind]
  (let [st @state]
    (when (:root st)
      (let [dir (target-dir st)]
        (swap! state #(-> %
                          (assoc :creating {:kind kind :dir dir :name ""} :confirm-delete nil)
                          (update :expanded conj dir)))
        (load-listing! dir)))))

(defn- commit-create! [name]
  (let [{:keys [kind dir]} (:creating @state)
        res (if (= :dir kind) (f/create-folder! dir name) (f/create-file! dir name))]
    (case (:error res)
      nil       (do (swap! state assoc :creating nil)
                    (load-listing! dir)
                    (toast! (t :toast/created (fs/file-name (:path res))))
                    (if (= :dir kind)
                      (swap! state #(-> % (update :expanded conj (:path res)) (assoc :selected (:path res))))
                      (open-file! (:path res))))
      :exists   (toast! (t :toast/exists (:name res)) true)
      :bad-name (toast! (t :toast/bad-name) true)
      (toast! (t :toast/error (:message res)) true))))

(defn- trash! [path]
  (let [open (filter #(or (= % path) (str/starts-with? % (str path "/"))) (:tabs @state))]
    (doseq [p open] (bw/drop-buffer! p))
    (swap! state (fn [st] (-> st
                              (update :tabs #(vec (remove (set open) %)))
                              (update :docs #(apply dissoc % open))
                              (update :active #(if (some #{%} open) nil %))))))
  (swap! state #(update % :active (fn [a] (or a (last (:tabs %))))))
  (if (or (gs/trash! path) (f/trash-by-hand! path platform/mac?))
    (toast! (t :toast/trashed (fs/file-name path)))
    (toast! (t :toast/error (str path)) true))
  (swap! state assoc :confirm-delete nil :selected nil)
  (refresh-listings!))

;; ---------------------------------------------------------------------------
;; keyboard
;; ---------------------------------------------------------------------------

(defn- zoom! [d]
  (swap! state update :font-size #(-> (if (zero? d) 15 (+ % d)) (max 10) (min 32)))
  (apply-theme!))

(defn- on-key [{:keys [key ctrl? cmd? shift?]}]
  (let [k (str/lower-case (str key))
        ;; Command on a Mac does what Ctrl does elsewhere
        ctrl? (or ctrl? (and platform/mac? cmd?))]
    (cond
      (and (= k "f5") shift?)                          (do (stop!) true)
      (or (= k "f5") (and ctrl? (#{"r" "return"} k)))  (do (run!) true)
      (and ctrl? (= k "s"))                            (do (save-all!) (toast! (t :toast/saved)) true)
      (and ctrl? (= k "n"))                            (do (start-creating! :file) true)
      (and ctrl? (= k "w"))                            (do (some-> (:active @state) close-tab!) true)
      (and ctrl? (#{"plus" "equal" "kp_add"} k))       (do (zoom! 1) true)
      (and ctrl? (#{"minus" "kp_subtract"} k))         (do (zoom! -1) true)
      (and ctrl? (= k "0"))                            (do (zoom! 0) true)
      (and ctrl? (= k "d") @process)                   (do (runner/close-input! @process)
                                                           (console! [:sys (str (t :run/eof) "\n")]) true)
      (and ctrl? (#{"tab" "iso_left_tab"} k))
      (let [{:keys [tabs active]} @state]
        (when (seq tabs)
          (let [i (.indexOf ^java.util.List tabs active)
                n (count tabs)]
            (swap! state assoc :active (nth tabs (mod ((if shift? dec inc) i) n)))))
        true)
      (= k "escape")
      (cond (:creating @state)       (do (swap! state assoc :creating nil) true)
            (:confirm-delete @state) (do (swap! state assoc :confirm-delete nil) true)
            :else nil)
      :else nil)))

;; ---------------------------------------------------------------------------
;; dispatch
;; ---------------------------------------------------------------------------

(defn handle! [event [action & args]]
  (let [ev-args (:gtkiccup/args event)]
    (case action
      :key            (on-key (first ev-args))
      :root/choose    (choose-root!)
      :tree/click     (let [[path dir?] args]
                        (swap! state assoc :selected path :confirm-delete nil)
                        (if dir?
                          (if (contains? (:expanded @state) path)
                            (swap! state update :expanded disj path)
                            (do (swap! state update :expanded conj path) (load-listing! path)))
                          (open-file! path)))
      :tree/new       (start-creating! (first args))
      :tree/refresh   (refresh-listings!)
      :tree/delete    (when-let [s (:selected @state)]
                        (swap! state assoc :confirm-delete s :creating nil))
      :tree/delete-yes (trash! (first args))
      :tree/delete-no  (swap! state assoc :confirm-delete nil)
      :create/change  (swap! state assoc-in [:creating :name] (first ev-args))
      :create/commit  (commit-create! (first ev-args))
      :create/cancel  (swap! state assoc :creating nil)
      :tab/activate   (swap! state assoc :active (first args) :selected (first args))
      :tab/close      (close-tab! (first args))
      :run            (run!)
      :stop           (stop!)
      :stdin/change   (swap! state assoc :stdin (first ev-args))
      :stdin/send     (send-stdin! (first ev-args))
      :stdin/eof      (when-let [h @process]
                        (runner/close-input! h)
                        (console! [:sys (str (t :run/eof) "\n")]))
      :bottom/tab     (swap! state assoc :bottom (first args))
      :console/clear  (clear-console!)
      :diag/goto      (let [[file line col] args] (goto! file line col))
      :theme/set      (do (swap! state assoc :theme (first args)) (apply-theme!))
      :font/zoom      (zoom! (first args))
      :compiler/recheck (check-compiler!)
      :compiler/copy  (do (gs/copy-to-clipboard! (:window @ui/current) (first args))
                          (toast! (t :compiler/copied)))
      (binding [*out* *err*] (println "[bbcecko] unknown action" action)))))

;; ---------------------------------------------------------------------------
;; view: pieces
;; ---------------------------------------------------------------------------

(defn- badge [path]
  (if-let [k (f/kind path)]
    [:label {:class ["badge" (name k)] :label (case k :cpp "C++" :h "H" :c "C" :txt "TXT")}]
    [:icon {:icon "text-x-generic-symbolic" :size 14 :width 22}]))

(defn- tree-row [{:keys [path name dir? depth]} {:keys [selected expanded docs active]}]
  (let [open? (expanded path)]
    [:button {:key path
              :class (cls "tree-row" (when (= selected path) "selected")
                          (when (= active path) "open-file") (when dir? "dir"))
              :on-click [:tree/click path dir?]
              :focusable false}
     [:hbox {:spacing 6 :margin-start (* depth 14)}
      (if dir?
        [:icon {:icon (if open? "pan-down-symbolic" "pan-end-symbolic") :size 12}]
        [:label {:width 12 :label ""}])
      (if dir?
        [:icon {:class "folder" :icon (if open? "folder-open-symbolic" "folder-symbolic") :size 16}]
        (badge path))
      [:label {:class "name" :label name :xalign 0 :hexpand true :ellipsize :middle}]
      (when (get-in docs [path :dirty?])
        [:label {:class "accent" :label "●"}])]]))

(defn- create-row [root {:keys [kind name dir]}]
  [:vbox {:class "new-entry" :spacing 4}
   [:label {:class "faint tiny" :xalign 0 :ellipsize :start
            :label (str "📁 " (let [r (f/relative root dir)] (if (= "" r) "./" (str r "/"))))}]
   [:entry {:value name
            :class "mono"
            :placeholder (t (if (= :dir kind) :explorer/folder-placeholder :explorer/file-placeholder))
            :on-change [:create/change]
            :on-activate [:create/commit]
            :replicant/on-mount (fn [e] (gs/widget-grab-focus (ui/widget e)))}]])

(defn- confirm-row [path]
  [:vbox {:class "banner" :spacing 8}
   [:label {:label (t :explorer/confirm-delete (fs/file-name path)) :wrap true :xalign 0}]
   [:hbox {:spacing 6}
    [:button {:class "pill" :label (t :explorer/yes-delete) :on-click [:tree/delete-yes path]}]
    [:button {:label "✕" :on-click [:tree/delete-no]}]]])

(defn- explorer [{:keys [root listings expanded creating confirm-delete selected] :as st}]
  (let [rows (tree-rows listings expanded root 0)]
    [:vbox {:class "panel" :width 230}
     [:hbox {:class "panel-head" :spacing 2}
      [:label {:class "panel-title" :label (t :explorer/title) :xalign 0 :hexpand true}]
      [:button {:class "icon-btn" :icon "document-new-symbolic" :tooltip (t :explorer/new-file)
                :on-click [:tree/new :file] :focusable false}]
      [:button {:class "icon-btn" :icon "folder-new-symbolic" :tooltip (t :explorer/new-folder)
                :on-click [:tree/new :dir] :focusable false}]
      [:button {:class "icon-btn" :icon "user-trash-symbolic" :tooltip (t :explorer/delete)
                :sensitive (boolean selected)
                :on-click [:tree/delete] :focusable false}]
      [:button {:class "icon-btn" :icon "view-refresh-symbolic" :tooltip (t :explorer/refresh)
                :on-click [:tree/refresh] :focusable false}]]
     (when creating (create-row root creating))
     (when confirm-delete (confirm-row confirm-delete))
     [:scrolled {:vexpand true :hscroll :never}
      [:vbox {:spacing 1 :margin-bottom 8}
       (if (and (empty? rows) (get listings root))
         [:label {:class "muted" :label (t :explorer/empty) :wrap true :margin 16}]
         (for [row rows] (tree-row row st)))]]]))

(defn- tab-strip [{:keys [tabs active docs root]}]
  [:scrolled {:hscroll :automatic :vscroll :never :class "tabs"}
   [:hbox {:spacing 2}
    (for [p tabs]
      [:hbox {:key p :class (cls "tab" (when (= p active) "active")) :spacing 2}
       [:button {:on-click [:tab/activate p] :focusable false :tooltip (f/relative root p)}
        [:hbox {:spacing 6}
         (badge p)
         [:label {:label (str (fs/file-name p))}]]]
       [:button {:on-click [:tab/close p] :focusable false :tooltip (t :tab/close)}
        (if (get-in docs [p :dirty?])
          [:label {:class "dirty" :label "●"}]
          [:icon {:icon "window-close-symbolic" :size 12}])]])]])

(defn- compiler-banner [{:keys [compiler]}]
  (when (= :missing (:status compiler))
    (let [{:keys [cmd distro]} (:hint compiler)]
      [:vbox {:class "banner" :spacing 8}
       [:hbox {:spacing 8}
        [:icon {:icon "dialog-warning-symbolic" :size 18}]
        [:label {:class "title" :label (t :compiler/missing-title) :xalign 0}]]
       [:label {:label (str (t (if platform/mac? :compiler/missing-sub-mac :compiler/missing-sub))
                            " (" distro ")")
                :wrap true :xalign 0}]
       [:hbox {:spacing 8}
        [:label {:class "cmd" :label cmd :selectable true :xalign 0 :hexpand true}]
        [:button {:class "pill" :label (t :compiler/copy) :on-click [:compiler/copy cmd]}]
        [:button {:class "pill" :label (t :compiler/recheck) :on-click [:compiler/recheck]}]]])))

(defn- editor-panel [{:keys [active docs theme diagnostics goto] :as st}]
  [:vbox {:class "panel" :hexpand true :vexpand true}
   (if active
     [:vbox {:vexpand true}
      (tab-strip st)
      [:vbox {:class "editor-wrap" :vexpand true :overflow :hidden}
       [:code-editor {:vexpand true :hexpand true
                      :doc active
                      :initial-text (get-in docs [active :text])
                      :scheme (th/scheme-id theme)
                      :marks (vec (for [d diagnostics
                                        :when (and (= (:file d) active) (:line d))]
                                    (select-keys d [:line :severity])))
                      :goto goto
                      :emit editor-event}]]]
     [:vbox {:vexpand true :valign :center :halign :center :spacing 12 :margin 24}
      [:icon {:class "big-icon" :icon "accessories-text-editor-symbolic" :size 64}]
      [:label {:class "empty-title" :label (t :editor/empty-title)}]
      [:label {:class "muted" :label (t :editor/empty-sub)}]
      [:hbox {:halign :center :spacing 8 :margin-top 8}
       [:button {:class "primary" :label (str "＋ " (t :explorer/new-file)) :on-click [:tree/new :file]}]]
      [:label {:class "kbd" :label (t :keys/hint) :margin-top 12}]])])

(defn- pretty-message
  "GCC quotes code in ‘…’. Those bits become bold monospace, so the eye finds
   the identifier the message is about."
  [msg]
  (-> (escape msg)
      (str/replace #"[‘'`]([^’']+)[’']" "‘<b><tt>$1</tt></b>’")))

(defn- diag-card [root {:keys [file line col severity message snippet notes tip raw? linker?]} i]
  (let [warn? (= :warning severity)]
    [:button {:key (str i) :class (cls "diag-card" (when warn? "warning"))
              :on-click [:diag/goto file line col]
              :sensitive true :focusable false}
     [:vbox {:spacing 6}
      [:hbox {:spacing 8}
       [:label {:class (cls "sev" (when warn? "warning")) :label (t (if warn? :diag/warning :diag/error))}]
       [:label {:class "where" :xalign 0 :hexpand true :ellipsize :start
                :label (cond line (str (f/relative root file) " · " (t :diag/line line (or col 1)))
                             linker? (t :diag/linker)
                             :else "")}]]
      (if raw?
        [:label {:class "snippet" :label message :xalign 0 :wrap true :selectable true}]
        [:label {:class "msg" :markup (pretty-message message) :xalign 0 :wrap true}])
      (when (seq snippet)
        [:label {:class "snippet" :xalign 0 :ellipsize :end
                 :label (str (format "%4d │ " line) (str/replace snippet "\t" "    "))}])
      (for [n notes]
        [:label {:class "note" :markup (str "↳ " (pretty-message n)) :xalign 0 :wrap true}])
      (when tip [:label {:class "tip" :label (t tip) :xalign 0 :wrap true}])]]))

(defn- run-status [{:keys [run]}]
  (let [s (:status run)]
    (when (not= :idle s)
      [:label {:class ["status-pill" (name s)]
               :label (str (case s :running "● " :compiling "◌ " :failed "✖ " "")
                           (t (keyword "status" (name s))))}])))

(defn- bottom-panel [{:keys [bottom console diagnostics theme stdin root run] :as st}]
  (let [errors (count (filter #(= :error (:severity %)) diagnostics))
        warns  (count (filter #(= :warning (:severity %)) diagnostics))
        running? (= :running (:status run))]
    [:vbox {:class "panel" :height 160}
     [:hbox {:class "panel-head" :spacing 6}
      [:hbox {:class "seg" :spacing 4}
       [:button {:class (cls (when (= bottom :output) "active")) :on-click [:bottom/tab :output] :focusable false}
        [:hbox {:spacing 6}
         [:icon {:icon "utilities-terminal-symbolic" :size 14}]
         [:label {:label (t :bottom/output)}]]]
       [:button {:class (cls (when (= bottom :problems) "active")) :on-click [:bottom/tab :problems] :focusable false}
        [:hbox {:spacing 6}
         [:icon {:icon "dialog-error-symbolic" :size 14}]
         [:label {:label (t :bottom/problems)}]
         (when (pos? errors) [:label {:class "count" :label (str errors)}])
         (when (pos? warns) [:label {:class "count warn" :label (str warns)}])]]]
      [:label {:hexpand true :label ""}]
      [:hbox {:key "status"} (run-status st)]
      (when running?
        [:button {:key "eof" :class "pill" :label (t :bottom/eof) :on-click [:stdin/eof] :focusable false}])
      [:button {:key "clear" :class "icon-btn" :icon "edit-clear-all-symbolic" :tooltip (t :bottom/clear)
                :on-click [:console/clear] :focusable false}]]
     (if (= bottom :problems)
       [:scrolled {:vexpand true :hscroll :never}
        [:vbox {:spacing 0 :margin-top 2}
         (if (empty? diagnostics)
           [:label {:class "muted" :label (t :bottom/no-problems) :margin 20}]
           (map-indexed (fn [i d] (diag-card root d i)) diagnostics))]]
       [:vbox {:vexpand true}
        [:overlay {:vexpand true :class "console-wrap"}
         [:console {:vexpand true
                    :segments (:segments console)
                    :epoch (:epoch console)
                    :base (:base console 0)
                    :palette (:console (th/theme theme))}]
         (when (and (empty? (:segments console)) (= :idle (:status run)))
           [:label {:slot :over :class "faint" :label (t :bottom/idle) :halign :center :valign :center}])]
        [:hbox {:class "stdin-row" :spacing 8}
         [:label {:class "prompt" :label "›"}]
         [:entry {:ref "stdin" :class "mono" :hexpand true
                  :value stdin
                  :sensitive running?
                  :placeholder (t (if running? :stdin/placeholder :stdin/not-running))
                  :on-change [:stdin/change]
                  :on-activate [:stdin/send]}]]])]))

(defn- theme-menu [{:keys [theme font-size]}]
  [:popover-button {:tooltip (t :theme/tooltip) :ref "theme-menu" :class "theme-btn"}
   [:label {:label "🎨" :class "theme-emoji"}]
   [:vbox {:slot :popover :spacing 4 :width 240}
    [:label {:class "menu-title" :label (t :theme/title) :xalign 0}]
    (for [{:keys [id emoji] :as th} th/themes]
      [:button {:key (name id) :class (cls "theme-item" (when (= id theme) "active"))
                :on-click [:theme/set id] :focusable false}
       [:hbox {:spacing 10}
        [:hbox {:class ["swatch" (str "swatch-" (name id))] :width 34 :height 22}]
        [:label {:label (str emoji "  " (get-in th [:name i18n/*lang*])) :xalign 0 :hexpand true}]
        (when (= id theme) [:icon {:icon "object-select-symbolic" :size 14}])]])
    [:separator {:margin-top 6 :margin-bottom 2}]
    [:label {:class "menu-title" :label (t :theme/font) :xalign 0}]
    [:hbox {:spacing 6 :margin-start 6 :margin-end 6 :margin-bottom 4}
     [:button {:class "pill" :label "A−" :on-click [:font/zoom -1] :focusable false}]
     [:label {:class "mono" :label (str font-size " px") :hexpand true}]
     [:button {:class "pill" :label "A+" :on-click [:font/zoom 1] :focusable false}]]]])

(defn- run-button [{:keys [run active compiler] :as st}]
  (case (:status run)
    :running   [:button {:class "stop-btn" :label (t :run/stop) :tooltip (t :run/stop-tooltip)
                         :on-click [:stop] :focusable false}]
    :compiling [:button {:class "run-btn" :label (t :run/compiling "") :sensitive false :focusable false}]
    [:button {:class "run-btn" :label (t :run/run) :focusable false
              :sensitive (boolean (can-run? st))
              :tooltip (cond (not= :ok (:status compiler)) (t :run/no-compiler)
                             (not (and active (f/runnable? active))) (t :run/no-file)
                             :else (t :run/tooltip))
              :on-click [:run]}]))

(defn- topbar [{:keys [root] :as st}]
  [:window-handle
   [:center-box {:class "topbar"}
    [:hbox {:slot :start :spacing 10}
     ;; a Mac puts close/minimise/zoom on the left
     (when platform/mac? [:window-controls {:side :start :valign :center}])
     [:label {:class "brand-mark" :label "C++" :valign :center}]
     [:label {:class "brand" :label (t :app/name) :valign :center}]
     (when root
       [:button {:class "root-chip" :on-click [:root/choose] :tooltip (t :root/change)
                 :focusable false :valign :center :margin-start 8}
        [:hbox {:spacing 6}
         [:icon {:icon "folder-symbolic" :size 14}]
         [:label {:label (f/display-path root) :ellipsize :start :max-width-chars 40}]]])]
    (when root (run-button st))
    [:hbox {:slot :end :spacing 6}
     (theme-menu st)
     [:window-controls {:side :end :valign :center}]]]])

(defn- statusbar [{:keys [compiler active root cursor]}]
  [:hbox {:class "statusbar" :spacing 14}
   (case (:status compiler)
     :ok      [:label {:label (str "● " (or (some->> (:version compiler) (re-find #"\d+\.\d+(\.\d+)?") first
                                                     (str (:name compiler) " "))
                                        (:name compiler)))
                       :tooltip (:version compiler)}]
     :missing [:label {:label (str "● " (t :run/no-compiler))}]
     [:label {:label "…"}])
   [:label {:label (if (and root active) (f/relative root active) "") :hexpand true :xalign 0
            :ellipsize :start}]
   (when active [:label {:label (apply t :status/line cursor)}])
   [:label {:class "faint" :label (t :keys/hint)}]])

(defn- welcome [{:keys [theme] :as st}]
  [:vbox {:vexpand true :hexpand true :valign :center :halign :center :spacing 18}
   [:vbox {:class "panel welcome-card" :spacing 14 :width 560}
    [:hbox {:spacing 12 :halign :center}
     [:label {:class "brand-mark" :label "C++" :valign :center}]
     [:label {:class "hero" :label (t :app/name)}]]
    [:label {:class "hero-sub" :label (t :welcome/title) :halign :center}]
    [:label {:class "muted" :label (t :welcome/sub) :wrap true :justify :center :halign :center}]
    [:button {:class "primary" :halign :center :margin-top 6 :on-click [:root/choose]}
     [:hbox {:spacing 8}
      [:icon {:icon "folder-open-symbolic" :size 16}]
      [:label {:label (t :root/choose)}]]]
    [:label {:class "faint small" :label (t :welcome/tip) :halign :center}]
    [:separator {:margin-top 6}]
    [:hbox {:spacing 8 :halign :center}
     (for [{:keys [id]} th/themes]
       [:button {:key (name id) :class (cls "theme-item" (when (= id theme) "active"))
                 :on-click [:theme/set id] :focusable false
                 :tooltip (get-in (th/theme id) [:name i18n/*lang*])}
        [:hbox {:class ["swatch" (str "swatch-" (name id))] :width 40 :height 26}]])]]
   (compiler-banner st)])

(defn- toast [{:keys [toast]}]
  (when toast
    [:label {:slot :over :class (cls "toast" (when (:error? toast) "error"))
             :label (:text toast) :halign :center :valign :end}]))

;; ---------------------------------------------------------------------------
;; view: the page
;; ---------------------------------------------------------------------------

(defn page []
  (let [{:keys [root] :as st} @state]
    [:overlay {:on-shortcut [:key]}
     [:vbox {:class "app"}
      (topbar st)
      (if-not root
        (welcome st)
        [:vbox {:vexpand true}
         (compiler-banner st)
         [:paned {:vexpand true :position 250 :margin-start 10 :margin-end 10 :shrink-start false}
          [:vbox {:slot :start} (explorer st)]
          [:paned {:slot :end :orientation :vertical :position 470}
           [:vbox {:slot :start :vexpand true} (editor-panel st)]
           [:vbox {:slot :end} (bottom-panel st)]]]
         (statusbar st)])]
     (toast st)]))

;; ---------------------------------------------------------------------------
;; start
;; ---------------------------------------------------------------------------

(def bbc-window
  "A normal decorated window -- the desktop keeps its shadow and resize edges
   -- whose titlebar is an empty, hidden box. Our top bar, with a window
   handle and the desktop's own window controls, takes its place."
  {:ctor (fn []
           (let [w (g/window-new)
                 tb (gs/box-new 0 0)]
             (gs/widget-set-visible tb 0)
             (gs/window-set-titlebar w tb)
             (gs/widget-add-css-class w "bbc")
             (gs/window-set-icon-name w "cz.brdloush.Bbcecko")
             w))
   :set-content g/window-set-child})

(defn start!
  "Opens the window and blocks until it is closed. `root` may be nil."
  [{:keys [root file]}]
  (i18n/set-lang! (i18n/detect))
  (reset! config (f/load-config))
  (let [cfg @config]
    (swap! state assoc
           :theme (if (th/by-id (:theme cfg)) (:theme cfg) :midnight)
           :font-size (or (:font-size cfg) 15)))
  (ui/set-dispatch! #'handle!)
  ;; app id before gtk_init, so the desktop matches our .desktop file
  (g/set-prgname "cz.brdloush.Bbcecko")
  (g/gtk-init)
  (gs/source-init)
  (bw/style-dir! (th/write-schemes! (str (fs/path (f/cache-dir) "styles"))))
  (reset! css-provider (ui/load-css! "" 800))
  (apply-theme!)
  (check-compiler!)
  (let [root (or root
                 (let [r (:root @config)] (when (and r (fs/directory? r)) r)))]
    (when root (set-root! root))
    (when file (open-file! (str (fs/canonicalize file)))))
  (watch-persist!)
  (start-watcher!)
  (try
    (ui/run #'page
            :title "bbCéčko"
            :width 1280 :height 820
            :window bbc-window
            :app-id "cz.brdloush.Bbcecko"
            :app-name "bbCéčko")
    (finally
      ;; the buffers outlive the window, so unsaved work is still there
      (try (save-all!) (catch Throwable _ nil))
      (stop!)
      (persist! @state)
      (f/save-config! @config))))
