(ns bbcecko.main
  "Command line: bbcecko [folder-or-file] | --version | --doctor | --help

   A folder becomes the root. A file opens, with its folder as the root.
   With no argument the launcher passes the terminal's working directory
   (BBCECKO_CWD) when started from a terminal, and otherwise the last root is
   used.

   The app namespace is loaded lazily: it loads GTK through FFI on the way
   in, and --version must work without that."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def version "0.1.2")

(defn parse-args [args env-cwd]
  (let [arg (first (remove #(or (str/starts-with? % "-") (str/blank? %)) args))
        target (or arg env-cwd)]
    (cond
      (nil? target)              {}
      (fs/directory? target)     {:root (str (fs/canonicalize target))}
      (fs/regular-file? target)  {:root (str (fs/parent (fs/canonicalize target)))
                                  :file (str (fs/canonicalize target))}
      :else                      {})))

(defn- doctor
  "Checks what the app needs without opening a window: the GTK libraries
   load, and a compiler is there. Exit code 0 when all is well."
  []
  (println "bbcecko" version)
  (println "os:" (System/getProperty "os.name") (System/getProperty "os.arch"))
  (let [ok? (atom true)
        check (fn [what f]
                (try (println "  ok  " what (or (f) ""))
                     (catch Throwable t
                       (reset! ok? false)
                       (println "  FAIL" what "-" (ex-message t)))))]
    (check "GTK 4, GtkSourceView 5, GIO load"
           #(do (requiring-resolve 'bbcecko.app/start!) nil))
    (check "C++ compiler"
           #(let [c ((requiring-resolve 'bbcecko.compiler/detect))]
              (if (= :ok (:status c))
                (:version c)
                (throw (ex-info (str "not found; install with: "
                                     (:cmd ((requiring-resolve 'bbcecko.compiler/install-hint))))
                                {})))))
    @ok?))

(defn -main [& args]
  (cond
    (some #{"--version" "-v"} args) (println "bbcecko" version)
    (some #{"--help" "-h"} args)    (println "usage: bbcecko [folder | file.cpp] [--version] [--doctor]")
    (some #{"--doctor"} args)       (System/exit (if (doctor) 0 1))
    :else
    (do ((requiring-resolve 'bbcecko.app/start!)
         (parse-args args (System/getenv "BBCECKO_CWD")))
        (shutdown-agents)
        (System/exit 0))))
