(ns bbcecko.files
  "Everything that touches the disk and is not GTK: the settings file, folder
   listings for the explorer, reading a file for the editor, creating files.
   Pure enough to test without a window."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; settings
;; ---------------------------------------------------------------------------

(defn config-dir []
  (str (fs/path (or (System/getenv "XDG_CONFIG_HOME")
                    (str (fs/home) "/.config"))
                "bbcecko")))

(defn cache-dir []
  (str (fs/path (or (System/getenv "XDG_CACHE_HOME")
                    (str (fs/home) "/.cache"))
                "bbcecko")))

(defn- config-file [] (str (fs/path (config-dir) "config.edn")))

(defn load-config []
  (try
    (let [f (config-file)]
      (if (fs/exists? f) (edn/read-string (slurp f)) {}))
    (catch Exception _ {})))

(defonce ^:private save-lock (Object.))

(defn save-config! [cfg]
  (locking save-lock
   (try
    (fs/create-dirs (config-dir))
    (let [tmp (str (config-file) ".tmp")]
      (spit tmp (pr-str cfg))
      (fs/move tmp (config-file) {:replace-existing true :atomic-move true}))
    (catch Exception e
      (binding [*out* *err*] (println "[bbcecko] cannot save settings:" (ex-message e)))))))

;; ---------------------------------------------------------------------------
;; listings
;; ---------------------------------------------------------------------------

(def ^:private hidden-names
  #{"node_modules" "__pycache__" "obj" ".git"})

(defn- hidden? [name]
  (or (str/starts-with? name ".")
      (contains? hidden-names name)
      (= "a.out" name)
      (re-find #"\.(o|obj|exe|depend|layout)$" name)))

(defn list-dir
  "The visible entries of `dir`: folders first, then files, each sorted by
   name the way a person sorts (case-insensitive). Unreadable -> empty."
  [dir]
  (try
    (->> (fs/list-dir dir)
         (keep (fn [p]
                 (let [name (str (fs/file-name p))]
                   (when-not (hidden? name)
                     {:name name :path (str p) :dir? (fs/directory? p)}))))
         (sort-by (juxt (complement :dir?) (comp str/lower-case :name)))
         vec)
    (catch Exception _ [])))

(defn ext [path]
  (some-> (re-find #"\.([^./]+)$" (str path)) second str/lower-case))

(defn kind
  "Which badge a file gets in the explorer."
  [path]
  (case (ext path)
    ("cpp" "cc" "cxx" "c++") :cpp
    ("h" "hpp" "hh" "hxx")   :h
    "c"                      :c
    ("txt" "in" "out" "md" "csv" "dat") :txt
    nil))

(defn runnable? [path]
  (contains? #{:cpp :c} (kind path)))

;; ---------------------------------------------------------------------------
;; reading and writing
;; ---------------------------------------------------------------------------

(def max-open-bytes (* 2 1024 1024))

(defn read-text
  "{:text s} for a text file, {:error :too-big} or {:error :binary} otherwise.
   A NUL byte in the first 8 KB is what marks a file as binary."
  [path]
  (try
    (let [size (fs/size path)]
      (if (> size max-open-bytes)
        {:error :too-big}
        (let [bytes (fs/read-all-bytes path)]
          (if (some zero? (take 8192 bytes))
            {:error :binary}
            ;; CRLF from Windows classmates becomes plain newlines
            {:text (str/replace (String. ^bytes bytes "UTF-8") "\r\n" "\n")}))))
    (catch Exception e {:error :io :message (ex-message e)})))

(defn write-text! [path text]
  (spit path text))

(def cpp-template
  "#include <iostream>

using namespace std;

int main() {
    cout << \"Ahoj, svete!\" << endl;
    return 0;
}
")

(defn valid-name?
  "A plain file name: no slashes, not empty, not . or .."
  [name]
  (let [n (str/trim (str name))]
    (and (seq n)
         (not (re-find #"[/\\\u0000]" n))
         (not (#{"." ".."} n)))))

(defn with-default-ext
  "A new file with no extension becomes a .cpp: that is what students make."
  [name]
  (let [n (str/trim name)]
    (if (re-find #"\.[^.]+$" n) n (str n ".cpp"))))

(defn create-file!
  "Creates `name` in `dir`, with a hello-world body for C++ sources.
   Returns {:path p} or {:error :exists|:bad-name|:io}."
  [dir name]
  (if-not (valid-name? name)
    {:error :bad-name}
    (let [n (with-default-ext name)
          p (str (fs/path dir n))]
      (cond
        (fs/exists? p) {:error :exists :name n}
        :else (try
                (spit p (if (= :cpp (kind p)) cpp-template ""))
                {:path p}
                (catch Exception e {:error :io :message (ex-message e)}))))))

(defn create-folder! [dir name]
  (if-not (valid-name? name)
    {:error :bad-name}
    (let [p (str (fs/path dir (str/trim name)))]
      (if (fs/exists? p)
        {:error :exists :name (str/trim name)}
        (try (fs/create-dirs p) {:path p}
             (catch Exception e {:error :io :message (ex-message e)}))))))

(defn- unique-in
  "A name in `dir` that is not taken yet: x.cpp, x.2.cpp, x.3.cpp ..."
  [dir name]
  (let [[_ base ext] (re-matches #"(.*?)(\.[^.]*)?" name)]
    (first (for [i (cons nil (iterate inc 2))
                 :let [n (str base (when i (str "." i)) ext)]
                 :when (not (fs/exists? (fs/path dir n)))]
             n))))

(defn trash-by-hand!
  "Moves `path` into the user's trash without GIO. For when g_file_trash
   refuses: a folder on a filesystem with no trash of its own (tmpfs, a USB
   stick), or a GLib without trash support on the Mac. Linux gets the
   freedesktop layout (files/ plus an info/ record, so the desktop's trash
   can restore it); the Mac just ~/.Trash. Returns true when it worked."
  [path mac?]
  (try
    (let [name (str (fs/file-name path))]
      (if mac?
        (let [trash (str (fs/path (fs/home) ".Trash"))]
          (fs/create-dirs trash)
          (fs/move path (fs/path trash (unique-in trash name)))
          true)
        (let [trash (str (fs/path (or (System/getenv "XDG_DATA_HOME")
                                      (str (fs/home) "/.local/share"))
                                  "Trash"))
              files (str (fs/path trash "files"))
              info  (str (fs/path trash "info"))
              _     (fs/create-dirs files)
              _     (fs/create-dirs info)
              n     (unique-in files name)]
          (spit (str (fs/path info (str n ".trashinfo")))
                (str "[Trash Info]\nPath=" (fs/absolutize path)
                     "\nDeletionDate=" (.format (java.time.LocalDateTime/now)
                                                 (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))
                     "\n"))
          (fs/move path (fs/path files n))
          true)))
    (catch Exception _ false)))

(defn relative [root path]
  (try (str (fs/relativize root path)) (catch Exception _ (str path))))

(defn display-path
  "~ for the home folder, so the chip in the top bar stays short."
  [path]
  (let [home (str (fs/home))]
    (if (str/starts-with? (str path) home)
      (str "~" (subs (str path) (count home)))
      (str path))))
