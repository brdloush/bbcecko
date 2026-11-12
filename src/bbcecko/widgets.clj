(ns bbcecko.widgets
  "The GTK widgets bbcecko adds to gtkiccup's tag table:

     :code-editor     GtkSourceView in a scrolled window, one buffer per open file
     :console         read-only coloured output, appended segment by segment
     :paned           GtkPaned, a draggable split
     :window-handle   makes its child drag the window, like a titlebar
     :window-controls minimise / maximise / close, as the desktop lays them out
     :popover-button  a menu button whose :slot :popover child opens in a popover

   There is one editor and one console in the app, so their bookkeeping is a
   single atom each rather than a table keyed by widget."
  (:require [babashka.ffi :as ffi]
            [bbcecko.gtk :as gs]
            [clojure.string :as str]
            [gtkiccup.core :as ui]
            [gtkiccup.ffi :as g]))

(defn- report [what t]
  (binding [*out* *err*] (println (str "[bbcecko] " what ": " (ex-message t)))))

(defn- addr [p] (ffi/address p))

;; ---------------------------------------------------------------------------
;; :on-shortcut
;; ---------------------------------------------------------------------------

;; gtkiccup's :on-key reports ctrl/alt/shift/super. On a Mac the shortcut key
;; is Command, which GTK reports as META (or SUPER on some versions), so this
;; variant passes a :cmd? flag too. Same controller, same capture phase.
(def ^:private SUPER-MASK (bit-shift-left 1 26))
(def ^:private META-MASK (bit-shift-left 1 28))

(ui/register-signal!
  :on-shortcut "key-pressed"
  (fn [f _w [_ctrl keyval _keycode state _data]]
    (when-let [name (g/keyval-name keyval)]
      (f (assoc (g/modifiers state)
                :key name
                :cmd? (pos? (bit-and (or state 0) (bit-or SUPER-MASK META-MASK)))))))
  {:controller g/event-controller-key-new
   :attach :window
   :argtypes [:pointer :int :int :int :pointer]
   :rettype :int})

;; ---------------------------------------------------------------------------
;; :code-editor
;; ---------------------------------------------------------------------------

(defonce ^{:doc "path -> GtkSourceBuffer. Buffers outlive the view, so a file
  keeps its undo history and caret while another one is shown, and when the
  editor is rebuilt (no file open, then one again)."}
  buffers
  (atom {}))

(defonce ^{:doc "{:view ptr :doc path :props props} of the live editor."}
  editor
  (atom nil))

(defonce ^:private scheme-ready (atom false))

(defn style-dir!
  "Points GtkSourceView at our style schemes. Once is enough."
  [dir]
  (when (compare-and-set! scheme-ready false true)
    (let [sm (gs/scheme-manager-default)]
      (gs/scheme-manager-prepend-search-path sm dir)
      (gs/scheme-manager-force-rescan sm))))

(defn- scheme [id]
  (let [s (gs/scheme-manager-get-scheme (gs/scheme-manager-default) (str id))]
    (when-not (g/null? s) s)))

(defn- language-for
  "C++ for anything C-like: students' headers are C++ headers. Plain text
   otherwise -- a .txt input file needs no colours."
  [path]
  (let [ext (some-> (re-find #"\.([^./]+)$" (str path)) second str/lower-case)
        id  (case ext
              ("cpp" "cc" "cxx" "c++" "ino") "cpp"
              ("h" "hpp" "hh" "hxx")         "cpphdr"
              "c"                            "c"
              "md"                           "markdown"
              "py"                           "python3"
              ("sh" "bash")                  "sh"
              ("json")                       "json"
              nil)]
    (when id
      (let [l (gs/lang-manager-get-language (gs/lang-manager-default) id)]
        (when-not (g/null? l) l)))))

(defn- emit! [event]
  (when-let [f (get-in @editor [:props :emit])]
    (try (f event) (catch Throwable t (report "editor event failed" t)))))

(defn- path-of-buffer [buf]
  (let [a (addr buf)]
    (some (fn [[p b]] (when (= a (addr b)) p)) @buffers)))

(defn- cursor-of [buf]
  (gs/with-iters [it]
    (gs/text-buffer-get-iter-at-mark buf it (gs/text-buffer-get-insert buf))
    [(inc (gs/text-iter-get-line it)) (inc (gs/text-iter-get-line-offset it))]))

(def ^:private modified-cb
  (delay
    (ffi/callback (ffi/global-arena)
                  (fn [buf _data]
                    (try
                      (when-let [path (path-of-buffer buf)]
                        (emit! [:modified path (g/<-gbool (gs/text-buffer-get-modified buf))]))
                      (catch Throwable t (report "modified-changed" t))))
                  [:pointer :pointer] :void)))

(def ^:private cursor-cb
  (delay
    (ffi/callback (ffi/global-arena)
                  (fn [buf _pspec _data]
                    (try
                      (when (= (path-of-buffer buf) (:doc @editor))
                        (emit! (into [:cursor] (cursor-of buf))))
                      (catch Throwable t (report "cursor-position" t))))
                  [:pointer :pointer :pointer] :void)))

(defn- new-buffer! [path text scheme-id]
  (let [buf (gs/source-buffer-new-with-language (or (language-for path) ffi/null))]
    (gs/source-buffer-set-highlight-matching-brackets buf 1)
    (when-let [s (scheme scheme-id)] (gs/source-buffer-set-style-scheme buf s))
    ;; loading the file must not be the first undo step
    (gs/text-buffer-begin-irreversible buf)
    (gs/text-buffer-set-text buf (str text) -1)
    (gs/text-buffer-end-irreversible buf)
    (gs/text-buffer-set-modified buf 0)
    (gs/with-iters [it]
      (gs/text-buffer-get-start-iter buf it)
      (gs/text-buffer-place-cursor buf it))
    (g/signal-connect-data buf "modified-changed" @modified-cb ffi/null ffi/null 0)
    (g/signal-connect-data buf "notify::cursor-position" @cursor-cb ffi/null ffi/null 0)
    (swap! buffers assoc path buf)
    buf))

(defn buffer-text
  "The current text of an open file, or nil. GTK thread only."
  [path]
  (when-let [buf (get @buffers path)]
    (gs/with-iters [s e]
      (gs/text-buffer-get-start-iter buf s)
      (gs/text-buffer-get-end-iter buf e)
      (gs/ptr->string-free (gs/text-buffer-get-text buf s e 0)))))

(defn mark-saved!
  "Clears the modified flag after the file was written. GTK thread only."
  [path]
  (when-let [buf (get @buffers path)]
    (gs/text-buffer-set-modified buf 0)))

(defn drop-buffer!
  "Forgets a closed file's buffer. GTK thread only."
  [path]
  (when-let [buf (get @buffers path)]
    (swap! buffers dissoc path)
    (g/object-unref buf)))

(defn rename-buffer!
  "Keeps an open file's buffer when the file is renamed or moved."
  [from to]
  (when-let [buf (get @buffers from)]
    (swap! buffers #(-> % (dissoc from) (assoc to buf)))))

(defn focus-editor! []
  (when-let [v (:view @editor)] (gs/widget-grab-focus v)))

(defn- set-mark-attributes! [view]
  (with-open [arena (ffi/confined-arena)]
    (doseq [[cat icon color prio] [["error" "dialog-error-symbolic" "rgba(255,70,90,0.22)" 20]
                                   ["warning" "dialog-warning-symbolic" "rgba(255,190,40,0.16)" 10]]]
      (let [attrs (gs/mark-attributes-new)
            rgba  (ffi/alloc arena 32 8)]
        (gs/rgba-parse rgba color)
        (gs/mark-attributes-set-background attrs rgba)
        (gs/mark-attributes-set-icon-name attrs icon)
        ;; the view takes its own reference
        (gs/source-view-set-mark-attributes view cat attrs prio)
        (g/object-unref attrs)))))

(defn- apply-marks! [buf marks]
  (gs/with-iters [s e]
    (gs/text-buffer-get-start-iter buf s)
    (gs/text-buffer-get-end-iter buf e)
    (gs/source-buffer-remove-source-marks buf s e ffi/null))
  (doseq [{:keys [line severity]} marks
          :when (pos-int? line)]
    (gs/with-iters [it]
      (gs/text-buffer-get-iter-at-line buf it (dec line))
      (gs/source-buffer-create-source-mark buf ffi/null
                                           (if (= :error severity) "error" "warning") it))))

(defn- scroll-to-cursor! [view buf]
  (gs/text-view-scroll-to-mark view (gs/text-buffer-get-insert buf) 0.15 0 0.0 0.3))

(defn- goto! [view buf {:keys [line col]}]
  (gs/with-iters [it]
    (gs/text-buffer-get-iter-at-line-offset buf it (max 0 (dec (or line 1))) (max 0 (dec (or col 1))))
    (gs/text-buffer-place-cursor buf it))
  (scroll-to-cursor! view buf)
  (gs/widget-grab-focus view))

(defn- apply-editor! [view p changed]
  (swap! editor assoc :props p)
  (when (changed :scheme)
    (when-let [s (scheme (:scheme p))]
      (doseq [b (vals @buffers)] (gs/source-buffer-set-style-scheme b s))))
  (when (changed :doc)
    (let [path (:doc p)
          buf  (or (get @buffers path) (new-buffer! path (:initial-text p) (:scheme p)))]
      (swap! editor assoc :doc path)
      (gs/text-view-set-buffer view buf)
      (gs/text-view-scroll-mark-onscreen view (gs/text-buffer-get-insert buf))
      (gs/widget-grab-focus view)
      (emit! (into [:cursor] (cursor-of buf)))))
  (let [buf (get @buffers (:doc p))]
    (when (and buf (or (changed :doc) (changed :marks)))
      (apply-marks! buf (:marks p)))
    (when (and buf (changed :goto) (= (:path (:goto p)) (:doc p)))
      (goto! view buf (:goto p)))))

(ui/register-widget! :code-editor
  {:ctor (fn [p]
           (let [sw   (g/scrolled-window-new)
                 view (gs/source-view-new)]
             (doto view
               (gs/source-view-set-show-line-numbers 1)
               (gs/source-view-set-show-line-marks 1)
               (gs/source-view-set-highlight-current-line 1)
               (gs/source-view-set-auto-indent 1)
               (gs/source-view-set-indent-on-tab 1)
               (gs/source-view-set-tab-width 4)
               (gs/source-view-set-indent-width 4)
               (gs/source-view-set-insert-spaces 1)
               (gs/source-view-set-smart-backspace 1)
               (gs/source-view-set-smart-home-end 1)
               (gs/text-view-set-monospace 1)
               (gs/text-view-set-left-margin 6)
               (gs/text-view-set-top-margin 8)
               (gs/text-view-set-bottom-margin 120)
               (g/widget-add-css-class "code"))
             (set-mark-attributes! view)
             (g/scrolled-window-set-child sw view)
             (reset! editor {:view view :doc nil :props p})
             (apply-editor! view p (set (keys p)))
             sw))
   :apply (fn [_sw p changed]
            (when-let [view (:view @editor)]
              (apply-editor! view p changed)))})

;; ---------------------------------------------------------------------------
;; :console
;; ---------------------------------------------------------------------------

(defonce ^:private console (atom nil))

(defn- escape-markup [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(def ^:private console-max-chars
  "Scrollback. Older text is cut from the top, like a terminal does."
  100000)

(defn- insert-segments! [{:keys [buf]} segments palette]
  (doseq [[kind text] segments
          :when (seq text)]
    (let [color (get palette kind (get palette :out "#cccccc"))
          bold? (#{:input :sys :ok} kind)
          markup (str "<span foreground=\"" color "\""
                      (when bold? " weight=\"bold\"")
                      (when (= kind :sys) " style=\"italic\"")
                      ">" (escape-markup text) "</span>")]
      (gs/with-iters [it]
        (gs/text-buffer-get-end-iter buf it)
        (gs/text-buffer-insert-markup buf it markup -1))))
  (let [n (gs/text-buffer-get-char-count buf)]
    (when (> n console-max-chars)
      (gs/with-iters [s e]
        (gs/text-buffer-get-start-iter buf s)
        (gs/text-buffer-get-iter-at-offset buf e (- n (* 4/5 console-max-chars)))
        (gs/text-buffer-delete buf s e))))
  nil)

(defonce ^:private stick-to-bottom (atom true))

(defn- follow-output!
  "Terminal behaviour: while the view is at the bottom, new output keeps it
   there; once the user scrolls up to read, it stays put. Hooked on the
   adjustment, because its `upper` only grows once GTK has laid the new text
   out -- scrolling right after an insert lands short of the real end."
  [sw]
  (let [adj (g/scrolled-window-get-vadjustment sw)
        bottom (fn [] (- (g/adjustment-get-upper adj) (g/adjustment-get-page-size adj)))]
    (g/signal-connect-data adj "changed"
                           (ffi/callback (ffi/global-arena)
                                         (fn [_ _]
                                           (when @stick-to-bottom
                                             (g/adjustment-set-value adj (bottom))
                                             ;; GTK validates long text in steps, so
                                             ;; `upper` may still grow: once more after
                                             ;; this frame's layout
                                             (ui/later! #(when @stick-to-bottom
                                                           (g/adjustment-set-value adj (bottom))))))
                                         [:pointer :pointer] :void)
                           ffi/null ffi/null 0)
    (g/signal-connect-data adj "value-changed"
                           (ffi/callback (ffi/global-arena)
                                         (fn [_ _]
                                           (reset! stick-to-bottom
                                                   (>= (g/adjustment-get-value adj) (- (bottom) 4))))
                                         [:pointer :pointer] :void)
                           ffi/null ffi/null 0)))

(defn- apply-console!
  "Segments are numbered from the start of the run: `:base` is how many the
   app has already dropped from the front of its list, so the widget can tell
   which ones it has not shown yet even when the list is trimmed."
  [p]
  (let [{:keys [shown epoch palette] :as st} @console
        segs (vec (:segments p))
        base (or (:base p) 0)
        total (+ base (count segs))]
    (if (or (not= epoch (:epoch p)) (not= palette (:palette p)) (< total shown))
      (do (gs/text-buffer-set-text (:buf st) "" -1)
          (reset! stick-to-bottom true)
          (insert-segments! st segs (:palette p)))
      (insert-segments! st (subvec segs (min (count segs) (max 0 (- shown base)))) (:palette p)))
    (swap! console assoc :shown total :epoch (:epoch p) :palette (:palette p))))

(ui/register-widget! :console
  {:ctor (fn [p]
           (let [sw   (g/scrolled-window-new)
                 view (g/text-view-new)
                 buf  (gs/text-view-get-buffer view)]
             (doto view
               (gs/text-view-set-editable 0)
               (gs/text-view-set-cursor-visible 0)
               (gs/text-view-set-monospace 1)
               (gs/text-view-set-wrap-mode 3)          ; GTK_WRAP_WORD_CHAR
               (gs/text-view-set-left-margin 14)
               (gs/text-view-set-right-margin 14)
               (gs/text-view-set-top-margin 10)
               (gs/text-view-set-bottom-margin 10)
               (g/widget-add-css-class "console-text"))
             (g/scrolled-window-set-child sw view)
             (reset! stick-to-bottom true)
             (follow-output! sw)
             (reset! console {:view view :buf buf :shown 0 :epoch nil :palette nil})
             (apply-console! p)
             sw))
   :apply (fn [_ p changed]
            (when (some changed [:segments :epoch :palette :base])
              (apply-console! p)))})

;; ---------------------------------------------------------------------------
;; :paned
;; ---------------------------------------------------------------------------

(ui/register-widget! :paned
  {:ctor (fn [p]
           (let [w (gs/paned-new (if (= :vertical (:orientation p)) 1 0))]
             (when-let [pos (:position p)] (gs/paned-set-position w (int pos)))
             (gs/paned-set-wide-handle w 1)
             (gs/paned-set-resize-start-child w (g/->gbool (:resize-start p true)))
             (gs/paned-set-resize-end-child w (g/->gbool (:resize-end p true)))
             (gs/paned-set-shrink-start-child w (g/->gbool (:shrink-start p false)))
             (gs/paned-set-shrink-end-child w (g/->gbool (:shrink-end p false)))
             w))
   :apply (fn [w p changed]
            ;; only an explicit change moves the divider: the user's drag wins
            (when (and (changed :position) (:position p))
              (gs/paned-set-position w (int (:position p)))))
   :append (fn [parent child props]
             (if (= :end (:slot props))
               (gs/paned-set-end-child parent child)
               (gs/paned-set-start-child parent child)))
   :remove (fn [parent _child props]
             (if (= :end (:slot props))
               (gs/paned-set-end-child parent ffi/null)
               (gs/paned-set-start-child parent ffi/null)))})

;; ---------------------------------------------------------------------------
;; window chrome
;; ---------------------------------------------------------------------------

(ui/register-widget! :window-handle
  {:ctor   (fn [_] (gs/window-handle-new))
   :apply  (fn [_ _ _] nil)
   :append (fn [parent child _] (gs/window-handle-set-child parent child))
   :remove (fn [parent _ _] (gs/window-handle-set-child parent ffi/null))})

(ui/register-widget! :window-controls
  {:ctor  (fn [p] (gs/window-controls-new (if (= :start (:side p)) 0 1)))
   :apply (fn [_ _ _] nil)})

(ui/register-widget! :popover-button
  {:ctor (fn [p]
           (let [mb  (gs/menu-button-new)
                 pop (gs/popover-new)]
             (gs/popover-set-has-arrow pop 0)
             (gs/menu-button-set-popover mb pop)
             (gs/menu-button-set-has-frame mb (g/->gbool (:has-frame p false)))
             (when-let [i (:icon p)] (gs/menu-button-set-icon-name mb i))
             (when-let [l (:label p)] (gs/menu-button-set-label mb (str l)))
             (g/widget-add-css-class pop "bbc-popover")
             mb))
   :apply (fn [w p changed]
            (when (and (changed :icon) (:icon p)) (gs/menu-button-set-icon-name w (:icon p)))
            (when (and (changed :label) (:label p)) (gs/menu-button-set-label w (str (:label p)))))
   :append (fn [mb child props]
             (if (= :popover (:slot props))
               (let [pop (gs/menu-button-get-popover mb)]
                 (gs/popover-set-child pop child))
               (gs/menu-button-set-child mb child)))
   :remove (fn [mb _child props]
             (if (= :popover (:slot props))
               (gs/popover-set-child (gs/menu-button-get-popover mb) ffi/null)
               (gs/menu-button-set-child mb ffi/null)))})
