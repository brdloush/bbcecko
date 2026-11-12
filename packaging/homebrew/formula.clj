;; Writes the Homebrew formula for bbcecko.
;;
;;   bb formula release 0.1.0 [out.rb]   formula for the GitHub tag v0.1.0
;;                                       (downloads the tarball for its sha256)
;;   bb formula local <dir>              formula + tarball of HEAD in <dir>, with a
;;                                       file:// url -- what CI installs to test it
;;
;; The formula builds one uberjar from the source at install time (bb fetches
;; gtkiccup and Replicant then), so the installed app needs no network and no
;; git. The launcher runs it with `bb -cp jar -m`, not `bb -jar`: with -jar,
;; babashka takes --version and --help for itself.
(require '[babashka.fs :as fs]
         '[babashka.http-client :as http]
         '[babashka.process :as p]
         '[bbcecko.main :as main]
         '[clojure.java.io :as io])

(defn sha256 [file]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream (io/file file))]
      (let [buf (byte-array 65536)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n) (.update md buf 0 n) (recur))))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn formula [{:keys [url sha version]}]
  (str "class Bbcecko < Formula
  desc \"Simple, friendly C++ editor for students: highlight, Run, readable errors\"
  homepage \"https://github.com/brdloush/bbcecko\"
  url \"" url "\"
  sha256 \"" sha "\"
  license \"MIT\"
  head \"https://github.com/brdloush/bbcecko.git\", branch: \"main\"

  depends_on \"borkdude/brew/babashka\"
  depends_on \"gtk4\"
  depends_on \"gtksourceview5\"
  depends_on \"adwaita-icon-theme\"

  def install
    bb = Formula[\"borkdude/brew/babashka\"].opt_bin/\"bb\"
    # one jar with the app, gtkiccup and Replicant: nothing to fetch at run time
    system bb, \"--config\", \"bb.edn\", \"uberjar\", \"bbcecko.jar\", \"-m\", \"bbcecko.main\"
    libexec.install \"bbcecko.jar\"
    (bin/\"bbcecko\").write <<~SH
      #!/bin/bash
      # Started from a terminal with no argument: the current folder is the root.
      if [ $# -eq 0 ] && [ -t 1 ]; then export BBCECKO_CWD=\"$PWD\"; fi
      exec \"#{bb}\" -Xmx256m -cp \"#{libexec}/bbcecko.jar\" -m bbcecko.main -- \"$@\"
    SH
  end

  def caveats
    <<~EOS
      Start it with:
        bbcecko                 # the folder you are in (from a terminal)
        bbcecko ~/skola/cpp     # a folder with your programs

      bbCéčko compiles with Apple's clang++. If it says the compiler is
      missing, install the command line tools:
        xcode-select --install

      Check that everything is in place:
        bbcecko --doctor
    EOS
  end

  test do
    assert_match version.to_s, shell_output(\"#{bin}/bbcecko --version\")
  end
end
"))

(defn -main [& [mode a b]]
  (case mode
    "release"
    (let [version (or a main/version)
          url (str "https://github.com/brdloush/bbcecko/archive/refs/tags/v" version ".tar.gz")
          tmp (str (fs/create-temp-file {:suffix ".tar.gz"}))
          resp (http/get url {:as :stream :throw false})]
      (when (not= 200 (:status resp))
        (binding [*out* *err*] (println "no tarball at" url "- is the tag v" version "pushed?"))
        (System/exit 1))
      (io/copy (:body resp) (io/file tmp))
      (let [rb (formula {:url url :sha (sha256 tmp) :version version})]
        (if b (spit b rb) (print rb))
        (flush)))

    "local"
    (let [dir (str (fs/absolutize (or a "target/formula")))
          version main/version
          tarball (str (fs/path dir (str "bbcecko-" version ".tar.gz")))]
      (fs/create-dirs dir)
      (p/shell "git" "archive" "--format=tar.gz" (str "--prefix=bbcecko-" version "/")
               "-o" tarball "HEAD")
      (spit (str (fs/path dir "bbcecko.rb"))
            (formula {:url (str "file://" tarball) :sha (sha256 tarball) :version version}))
      (println "wrote" (str (fs/path dir "bbcecko.rb")) "and" tarball))

    (do (println "usage: bb formula release [version] [out.rb] | bb formula local [dir]")
        (System/exit 1))))

(apply -main *command-line-args*)
