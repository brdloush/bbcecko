;; Headless screenshot driver: bb --config bb.edn dev/shot.clj <root> <out-prefix> [script...]
;; Starts the app, runs a scripted list of actions, shoots a PNG after each
;; :shot step, then closes. Run it inside gtkiccup's scripts/headless.
(require '[bbcecko.gtk :as gs] '[gtkiccup.refs :as refs] '[babashka.ffi :as ffi]
         '[bbcecko.app :as app] '[gtkiccup.core :as ui] '[gtkiccup.dev :as dev]
         '[clojure.edn :as edn])

(let [[root prefix & steps] *command-line-args*
      steps (map edn/read-string steps)
      th (future (app/start! {:root (not-empty root)}))]
  (Thread/sleep 2500)
  (doseq [s steps]
    (cond
      (= :shot (first s)) (println (dev/screenshot! (str prefix "-" (second s) ".png") (:window @ui/current) 1))
      (= :popup (first s)) (ui/on-gtk-thread! #((ffi/cfn "gtk_menu_button_popup" [:pointer] :void) (refs/resolve "theme-menu")))
      (= :shot-popover (first s))
      (println (dev/screenshot! (str prefix "-" (second s) ".png")
                                (ui/on-gtk-thread! #(gs/menu-button-get-popover (refs/resolve "theme-menu"))) 1))
      (= :sleep (first s)) (Thread/sleep (second s))
      (= :swap (first s)) (swap! app/state assoc-in (second s) (nth s 2))
      :else (ui/on-gtk-thread! #(app/handle! {:gtkiccup/args (vec (drop 1 s))} (vec (first s)))))
    (Thread/sleep 400))
  (ui/close!)
  (deref th 5000 nil)
  (System/exit 0))
