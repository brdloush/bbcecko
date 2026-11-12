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
;;
;; babashka itself is a resource of the formula, not a dependency: it is not in
;; homebrew-core, and Homebrew no longer taps a third-party tap for a
;; dependency -- `depends_on "borkdude/brew/babashka"` made every user run
;; `brew tap borkdude/brew` by hand first. The copy lives in this keg's
;; libexec, so it never clashes with a babashka installed on its own.
(require '[babashka.fs :as fs]
         '[cheshire.core]
         '[clojure.string]
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

(def bb-version
  "The babashka the formula carries. Bump it here, then re-release."
  "1.13.229")

(defn bb-assets
  "{\"macos-aarch64\" {:url .. :sha ..} ...} from the babashka release, using
   the sha256 digests GitHub publishes for each asset."
  [version]
  (let [rel (-> (http/get (str "https://api.github.com/repos/babashka/babashka/releases/tags/v" version)
                          {:headers {"Accept" "application/vnd.github+json"}})
                :body
                (cheshire.core/parse-string true))]
    (into {}
          (for [plat ["macos-aarch64" "macos-amd64" "linux-amd64"]
                :let [name (str "babashka-" version "-" plat ".tar.gz")
                      asset (first (filter #(= name (:name %)) (:assets rel)))]]
            (do (when-not (some-> asset :digest (clojure.string/starts-with? "sha256:"))
                  (throw (ex-info (str "no sha256 digest for " name) {:asset asset})))
                [plat {:url (:browser_download_url asset)
                       :sha (subs (:digest asset) 7)}])))))

(defn- bb-resource [{:keys [url sha]}]
  (str "      resource \"babashka\" do
        url \"" url "\"
        sha256 \"" sha "\"
      end
"))

(defn formula [{:keys [url sha version]}]
  (let [assets (bb-assets bb-version)]
  (str "class Bbcecko < Formula
  desc \"Simple, friendly C++ editor for students: highlight, Run, readable errors\"
  homepage \"https://github.com/brdloush/bbcecko\"
  url \"" url "\"
  sha256 \"" sha "\"
  license \"MIT\"
  head \"https://github.com/brdloush/bbcecko.git\", branch: \"main\"

  depends_on \"adwaita-icon-theme\"
  depends_on \"gtk4\"
  depends_on \"gtksourceview5\"

  # babashka runs the app. It is not in homebrew-core, and depending on the
  # borkdude/brew tap would make every user tap it by hand first, so the
  # formula carries its own copy, private to this keg.
  on_macos do
    on_arm do
" (bb-resource (assets "macos-aarch64")) "    end
    on_intel do
" (bb-resource (assets "macos-amd64")) "    end
  end
  on_linux do
    on_intel do
" (bb-resource (assets "linux-amd64")) "    end
  end

  def install
    resource(\"babashka\").stage { (libexec/\"bin\").install \"bb\" }
    bb = libexec/\"bin/bb\"
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
")))

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
