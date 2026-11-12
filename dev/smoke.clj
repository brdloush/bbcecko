;; End-to-end smoke test, with screenshots. Drives the real app through its
;; own actions, the way clicks would, and checks what happened.
;;
;;   bb smoke <out-dir>
;;
;; Copies examples/ to a temp folder, starts on the welcome screen, picks that
;; folder, runs a program with input, a broken program, an endless one, and
;; every theme. Writes PNGs to <out-dir>. Exits 1 if any check failed.
;;
;; The app runs on the MAIN thread and the checks on a worker: macOS lets
;; only the main thread create windows (NSWindow), and kills the process
;; otherwise. Linux does not care, which is how this was missed at first.
(require '[babashka.ffi :as ffi]
         '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[bbcecko.app :as app]
         '[bbcecko.gtk :as gs]
         '[bbcecko.platform :as platform]
         '[bbcecko.themes :as th]
         '[clojure.string :as str]
         '[gtkiccup.core :as ui]
         '[gtkiccup.dev :as dev]
         '[gtkiccup.refs :as refs])

(def out (str (fs/absolutize (or (first *command-line-args*) "shots"))))
(fs/create-dirs out)
(def work (str (fs/canonicalize (fs/create-temp-dir {:prefix "bbcecko-smoke"}))))
(fs/copy-tree "examples" work)
(defn f [name] (str (fs/path work name)))

(def failures (atom []))
(defn check [what ok?]
  (println (if ok? "  ok  " "  FAIL") what)
  (when-not ok? (swap! failures conj what)))

(defn act! [& action]
  (ui/on-gtk-thread! #(app/handle! {:gtkiccup/args (vec (rest action))} (vec (first action)))))
(defn act-args! [action & ev-args]
  (ui/on-gtk-thread! #(app/handle! {:gtkiccup/args (vec ev-args)} action)))

(defn shot [name]
  ;; a frame can be mid-update while output pours in: try a few times
  (loop [n 3]
    (Thread/sleep 400)
    (let [ok? (try (dev/screenshot! (str out "/" name ".png") (:window @ui/current) 1) true
                   (catch Throwable t (when (= 1 n) (println "  shot failed" name (ex-message t))) false))]
      (cond ok? (println "  shot" name)
            (> n 1) (recur (dec n))))))

(defn wait-for [what pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred @app/state) true
            (> (System/currentTimeMillis) deadline) (do (check (str what " (timed out)") false) false)
            :else (do (Thread/sleep 100) (recur))))))

(defn console-text [] (apply str (map second (get-in @app/state [:console :segments]))))
(defn status [] (get-in @app/state [:run :status]))

(defn checks []
(Thread/sleep 3000)
(println "platform:" platform/os)
(println "compiler:" (:compiler @app/state))

(try
  ;; 1. welcome
  (check "window is up" (some? (:window @ui/current)))
  (check "no root yet -> welcome" (nil? (:root @app/state)))
  (shot "01-welcome")

  ;; 2. pick the folder (what the folder dialog's callback does)
  (ui/on-gtk-thread! #(app/set-root! work))
  (check "explorer lists the examples"
         (some #(= "kalkulacka.cpp" (:name %)) (get-in @app/state [:listings work])))
  (check "compiler found" (wait-for "compiler" #(= :ok (get-in % [:compiler :status])) 15000))

  ;; 3. a program that asks for input
  (act! [:tree/click (f "kalkulacka.cpp") false])
  (check "file opens in a tab" (= (f "kalkulacka.cpp") (:active @app/state)))
  (shot "02-editor")
  (act! [:run])
  (wait-for "program starts" #(= :running (get-in % [:run :status])) 60000)
  (wait-for "first prompt" (fn [_] (str/includes? (console-text) "Zadej prvni")) 5000)
  (act-args! [:stdin/send] "6")
  (wait-for "second prompt" (fn [_] (str/includes? (console-text) "Zadej druhe")) 5000)
  (act-args! [:stdin/send] "7")
  (wait-for "program ends" #(= :done (get-in % [:run :status])) 10000)
  (check "sum is printed" (str/includes? (console-text) "Soucet je 13"))
  (check "product is printed" (str/includes? (console-text) "Soucin je 42"))
  (shot "03-run-with-input")

  ;; 4. a program that does not compile
  (act! [:tree/click (f "chyba.cpp") false])
  (act! [:run])
  (wait-for "compile fails" #(= :failed (get-in % [:run :status])) 60000)
  (let [errs (filter #(= :error (:severity %)) (:diagnostics @app/state))]
    (println "  diagnostics:" (mapv (juxt :line :col :message) errs))
    (check "errors have a line" (some :line errs))
    (check "the missing ; is reported near line 6-7" (some #(#{6 7} (:line %)) errs))
    (check "the undeclared variable is reported" (some #(re-find #"vysledek" (:message %)) errs)))
  (check "problems tab is shown" (= :problems (:bottom @app/state)))
  (shot "04-compile-errors")

  ;; 5. an endless loop, then Stop
  (act! [:bottom/tab :output])
  (act! [:tree/click (f "smycka.cpp") false])
  (act! [:run])
  (wait-for "loop starts" #(= :running (get-in % [:run :status])) 60000)
  (Thread/sleep 2000)
  (shot "05-endless-output")
  (act! [:stop])
  (check "stop ends the program" (wait-for "stopped" #(= :done (get-in % [:run :status])) 10000))

  ;; 6. files: create, then trash
  (act! [:tree/new :file])
  (act-args! [:create/commit] "novy")
  (check "new file is created as .cpp" (fs/exists? (f "novy.cpp")))
  (check "new file opens" (= (f "novy.cpp") (:active @app/state)))
  (act! [:tree/delete-yes (f "novy.cpp")])
  (Thread/sleep 300)
  (check "trash removes the file" (not (fs/exists? (f "novy.cpp"))))

  ;; 7. shortcuts (Ctrl, or Cmd on a Mac)
  (let [mod (if platform/mac? {:cmd? true} {:ctrl? true})
        before (:font-size @app/state)]
    (act-args! [:key] (assoc mod :key "plus"))
    (check "zoom shortcut" (= (inc before) (:font-size @app/state)))
    (act-args! [:key] (assoc mod :key "0")))

  ;; 8. every theme, and the theme menu
  (act! [:tree/click (f "kalkulacka.cpp") false])
  (doseq [{:keys [id]} th/themes]
    (act! [:theme/set id])
    (shot (str "06-theme-" (name id))))
  (act! [:theme/set :midnight])
  (ui/on-gtk-thread! #((ffi/cfn "gtk_menu_button_popup" [:pointer] :void) (refs/resolve "theme-menu")))
  (Thread/sleep 600)
  (try (dev/screenshot! (str out "/07-theme-menu.png")
                        (ui/on-gtk-thread! #(gs/menu-button-get-popover (refs/resolve "theme-menu"))) 1)
       (println "  shot 07-theme-menu")
       (catch Throwable t (println "  popover shot failed" (ex-message t))))
  (ui/on-gtk-thread! #(gs/menu-button-popdown (refs/resolve "theme-menu")))

  ;; 9. on a Mac, the real screen too: native window frame and all
  (when platform/mac?
    (Thread/sleep 500)
    (p/shell {:continue true} "screencapture" "-x" (str out "/08-mac-screen.png")))

  (catch Throwable t
    (check (str "unexpected error: " (ex-message t)) false)
    (.printStackTrace t)))
(ui/close!))

(def worker (future (checks)))
(app/start! {})           ; blocks on the main thread until close!
(deref worker 10000 nil)
(fs/delete-tree work)
(println)
(if (empty? @failures)
  (do (println "SMOKE OK") (System/exit 0))
  (do (println "SMOKE FAILED:" (count @failures)) (doseq [x @failures] (println "  -" x)) (System/exit 1)))
