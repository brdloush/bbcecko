(ns bbcecko.compiler
  "Finding a compiler, compiling one file, and turning GCC/Clang output into
   data the UI can show: one card per error, with the line, the message, the
   offending code and, for the classic beginner mistakes, a tip.

   GCC is asked for plain one-line diagnostics (-fdiagnostics-plain-output)
   in the C locale, so the format is stable and the messages stay English --
   which is what a student will paste into a search engine."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbcecko.platform :as platform]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; detection
;; ---------------------------------------------------------------------------

(def candidates
  "Preferred first. g++ is what the school's Code::Blocks uses. On a Mac,
   g++ is only a name for Apple's clang, so ask for clang++ directly."
  (if platform/mac?
    [{:cxx "clang++" :cc "clang"}]
    [{:cxx "g++"     :cc "gcc"}
     {:cxx "clang++" :cc "clang"}]))

(defn- mac-tools-installed?
  "On a Mac /usr/bin/clang++ always exists, but without the command line
   tools it only pops up an installer and fails. xcode-select knows."
  []
  (try (zero? (:exit (p/shell {:out :string :err :string :continue true}
                              "xcode-select" "-p")))
       (catch Exception _ false)))

(defn- version-of [exe]
  (try
    (let [{:keys [out exit]} (p/shell {:out :string :err :string :continue true
                                       :extra-env {"LC_ALL" "C"}}
                                      exe "--version")]
      (when (zero? exit) (str/trim (first (str/split-lines out)))))
    (catch Exception _ nil)))

(defn detect
  "{:status :ok :cxx path :cc path :name \"g++\" :version \"...\" :clang? b}
   or {:status :missing}."
  []
  (or (when (or (not platform/mac?) (mac-tools-installed?))
        (some (fn [{:keys [cxx cc]}]
                (when-let [path (fs/which cxx)]
                  (let [v (version-of (str path))]
                    (when v
                      {:status :ok
                       :name cxx
                       :cxx (str path)
                       :cc (some-> (fs/which cc) str)
                       :version v
                       :clang? (boolean (re-find #"(?i)clang" v))}))))
              candidates))
      {:status :missing}))

(defn- os-release []
  (try
    (into {}
          (for [line (str/split-lines (slurp "/etc/os-release"))
                :let [[_ k v] (re-matches #"([A-Z_]+)=\"?([^\"]*)\"?" line)]
                :when k]
            [k v]))
    (catch Exception _ {})))

(defn install-hint
  "The one command that installs a compiler on this system."
  ([] (if platform/mac?
        {:distro "macOS" :cmd "xcode-select --install"}
        (install-hint (os-release))))
  ([{:strs [ID ID_LIKE PRETTY_NAME]}]
   (let [ids (set (str/split (str ID " " ID_LIKE) #"\s+"))
         cmd (cond
               (some ids ["ubuntu" "debian" "linuxmint" "pop" "elementary"]) "sudo apt install build-essential"
               (some ids ["fedora" "rhel" "centos"]) "sudo dnf install gcc-c++"
               (some ids ["arch" "manjaro" "endeavouros"]) "sudo pacman -S gcc"
               (some ids ["opensuse" "suse" "opensuse-tumbleweed"]) "sudo zypper install gcc-c++"
               (some ids ["alpine"]) "sudo apk add build-base"
               :else "sudo apt install build-essential")]
     {:distro (or PRETTY_NAME "Linux") :cmd cmd})))

;; ---------------------------------------------------------------------------
;; compiling
;; ---------------------------------------------------------------------------

(defn output-path
  "Where the program for `src` goes: the cache, never the student's folder,
   so their folder only ever holds what they wrote."
  [cache-dir src]
  (let [h (format "%08x" (hash (str src)))
        base (str/replace (str (fs/file-name src)) #"\.[^.]+$" "")]
    (str (fs/path cache-dir "build" h base))))

(defn compile-cmd
  "GCC and Clang share the diagnostic format but not the flags that keep it
   plain: Clang rejects GCC's -fdiagnostics-plain-output."
  [{:keys [cxx cc clang?]} src out]
  (let [c?    (= "c" (some-> (re-find #"\.([^.]+)$" src) second str/lower-case))
        plain (if clang?
                ["-fno-color-diagnostics" "-fno-caret-diagnostics" "-fno-diagnostics-fixit-info"]
                ["-fdiagnostics-plain-output" "-fmessage-length=0" "-fdiagnostics-color=never"])]
    (if c?
      (-> [(or cc "gcc") "-std=c17" "-Wall" "-g" "-O0"] (into plain) (into ["-o" out src "-lm"]))
      (-> [cxx "-std=c++17" "-Wall" "-g" "-O0"] (into plain) (into ["-o" out src])))))

(def ^:private diag-re
  #"^(.+?):(\d+):(?:(\d+):)? (fatal error|error|warning|note): (.*)$")

(defn- tip-for [message]
  (condp re-find message
    #"expected .*[‘'];[’']" :tip/missing-semicolon
    #"‘(cout|cin|endl)’ was not declared|'(cout|cin|endl)' was not declared" :tip/cin-cout
    #"‘string’ was not declared|'string' was not declared|unknown type name 'string'" :tip/string
    #"was not declared in this scope|undeclared identifier" :tip/undeclared
    #"expected ‘}’|expected '}'|expected declaration before ‘}’|expected unqualified-id before ‘}’" :tip/expected-brace
    #"undefined reference to `main'|undefined reference to 'main'" :tip/no-main
    #"undefined reference to" :tip/undefined-ref
    nil))

(defn parse-diagnostics
  "GCC/Clang output -> [{:file :line :col :severity :message :notes :tip}].
   Notes attach to the diagnostic before them. Linker errors carry no line,
   so they come back with :line nil and the raw message."
  [output]
  (let [lines (str/split-lines (str output))]
    (loop [ls lines acc []]
      (if-let [line (first ls)]
        (if-let [[_ file ln col sev msg] (re-matches diag-re line)]
          (let [sev (case sev ("error" "fatal error") :error "warning" :warning :note)]
            (if (= :note sev)
              (recur (rest ls)
                     (if (seq acc)
                       (update-in acc [(dec (count acc)) :notes] (fnil conj []) msg)
                       acc))
              (recur (rest ls)
                     (conj acc {:file file :line (parse-long ln) :col (some-> col parse-long)
                                :severity sev :message msg :tip (tip-for msg)}))))
          (if-let [msg (or
                         ;; GNU ld
                         (second (re-find #"(?:\(\.text.*?\)|\.o:[^:]*): (undefined reference to .*)$" line))
                         ;; Apple ld: '  "foo()", referenced from:'
                         (some->> (re-find #"^\s+\"(.+)\", referenced from:" line) second
                                  (format "undefined reference to `%s'"))
                         ;; lld
                         (some->> (re-find #"undefined symbol: (.+)$" line) second
                                  (format "undefined reference to `%s'")))]
            (recur (rest ls)
                   (conj acc {:file nil :line nil :col nil :severity :error
                              :message msg :tip (tip-for msg) :linker? true}))
            (recur (rest ls) acc)))
        ;; the same undefined reference is reported once per caller
        (vec (distinct acc))))))

(defn compile!
  "Compiles `src` with `compiler`. Returns {:ok? :diagnostics :output :out :ms}."
  [compiler cache-dir src]
  (let [out (output-path cache-dir src)
        _   (fs/create-dirs (fs/parent out))
        t0  (System/currentTimeMillis)
        {:keys [exit err]} (apply p/shell {:out :string :err :string :continue true
                                     :dir (str (fs/parent src))
                                     :extra-env {"LC_ALL" "C.UTF-8" "LANG" "C.UTF-8"}}
                                  (compile-cmd compiler src out))
        diags (parse-diagnostics err)]
    {:ok? (zero? exit)
     :out out
     :output err
     :ms (- (System/currentTimeMillis) t0)
     :diagnostics (if (and (not (zero? exit)) (not-any? #(= :error (:severity %)) diags))
                    ;; failed, but nothing we could parse: show it raw
                    (conj diags {:file nil :line nil :severity :error
                                 :message (str/trim (str err)) :raw? true})
                    diags)}))
