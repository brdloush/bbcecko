(ns bbcecko.platform
  "Which OS we are on, and the few places that differ: the shortcut key
   (Ctrl, or Cmd on a Mac) and where Homebrew keeps its libraries."
  (:require [clojure.string :as str]))

(def os
  (let [n (str/lower-case (str (System/getProperty "os.name")))]
    (cond (str/includes? n "mac") :mac
          (str/includes? n "win") :windows
          :else :linux)))

(def mac? (= :mac os))

(def brew-prefixes
  "Apple Silicon first, then Intel."
  ["/opt/homebrew" "/usr/local"])
