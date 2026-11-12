(ns bbcecko.main
  "Command line: bbcecko [folder-or-file]

   A folder becomes the root. A file opens, with its folder as the root.
   With no argument the launcher passes the terminal's working directory
   (BBCECKO_CWD) when started from a terminal, and otherwise the last root is
   used."
  (:require [babashka.fs :as fs]
            [clojure.string]
            [bbcecko.app :as app]))

(defn parse-args [args env-cwd]
  (let [arg (first (remove #(or (.startsWith ^String % "-") (clojure.string/blank? %)) args))
        target (or arg env-cwd)]
    (cond
      (nil? target)              {}
      (fs/directory? target)     {:root (str (fs/canonicalize target))}
      (fs/regular-file? target)  {:root (str (fs/parent (fs/canonicalize target)))
                                  :file (str (fs/canonicalize target))}
      :else                      {})))

(defn -main [& args]
  (app/start! (parse-args args (System/getenv "BBCECKO_CWD")))
  (shutdown-agents)
  (System/exit 0))
