(ns bbcecko.runner
  "Runs the student's program with its stdin, stdout and stderr wired to the
   console.

   Two details matter for a program that asks for input:

   - stdout is a pipe, not a terminal, so C stdio would buffer it and a
     prompt like `cout << \"Zadej cislo: \"` would not show before `cin` waits.
     `stdbuf -o0` turns the buffering off (cout syncs with stdio by default,
     so it covers C++ too).
   - output is read char by char as it comes, not line by line, for the same
     reason: a prompt has no newline.

   Output is batched: reader threads append to a buffer, and a flusher hands
   it to the UI at most every 30 ms. A program printing in a tight loop
   would otherwise re-render the window thousands of times a second."
  (:require [babashka.fs :as fs]
            [babashka.process :as p])
  (:import [java.io InputStreamReader Writer OutputStreamWriter]
           [java.nio.charset StandardCharsets]))

(def flush-cap
  "At most this many characters reach the UI per flush. A program printing
   in an endless loop produces megabytes a second; the console only needs to
   show the newest part, and a text view fed that much would freeze."
  8000)

(defn- pump!
  "Reads `stream` until EOF, appending [kind text] chunks to `pending`."
  [stream kind pending]
  (future
    (try
      (with-open [r (InputStreamReader. stream StandardCharsets/UTF_8)]
        (let [buf (char-array 4096)]
          (loop []
            (let [n (.read r buf)]
              (when (pos? n)
                (swap! pending conj [kind (String. buf 0 n)])
                (recur))))))
      (catch Exception _ nil))))

(defn- merge-chunks
  "Neighbouring chunks of the same kind become one segment. StringBuilders,
   not str: a flood is thousands of chunks, and repeated str is quadratic."
  [chunks]
  (let [out (java.util.ArrayList.)]
    (doseq [[k ^String t] chunks]
      (let [last (when (pos? (.size out)) (.get out (dec (.size out))))]
        (if (and last (= k (first last)))
          (.append ^StringBuilder (second last) t)
          (.add out [k (StringBuilder. t)]))))
    (mapv (fn [[k sb]] [k (str sb)]) out)))

(defn- keep-tail
  "The last `cap` characters of `segs`, in order."
  [segs cap]
  (loop [acc '() left cap ss (rseq segs)]
    (if (or (empty? ss) (<= left 0))
      (vec acc)
      (let [[k t] (first ss)
            n (count t)]
        (if (<= n left)
          (recur (conj acc [k t]) (- left n) (rest ss))
          (vec (conj acc [k (subs t (- n left))])))))))

(defn start!
  "Starts `exe` in `dir`. Calls (on-output segments) with batches of
   [kind text], kind :out or :err, and finally (on-exit {:code n :ms n}).
   Returns a handle for send!, close-input! and stop!."
  [exe dir {:keys [on-output on-exit skipped-note]}]
  (let [cmd     (if-let [sb (fs/which "stdbuf")] [(str sb) "-o0" "-e0" exe] [exe])
        proc    (apply p/process {:dir dir :in :pipe :out :pipe :err :pipe
                            :extra-env {"LC_ALL" "C.UTF-8"}}
                                 cmd)
        pending (atom [])
        t0      (System/currentTimeMillis)
        stopped (atom false)
        writer  (OutputStreamWriter. (:in proc) StandardCharsets/UTF_8)
        out-f   (pump! (:out proc) :out pending)
        err-f   (pump! (:err proc) :err pending)
        flush!  (fn []
                  (let [chunks (first (reset-vals! pending []))]
                    (when (seq chunks)
                      (let [segs (merge-chunks chunks)
                            n    (reduce + (map (comp count second) segs))]
                        (on-output (if (> n flush-cap)
                                     (into [[:sys (or skipped-note "\n…\n")]]
                                           (keep-tail segs flush-cap))
                                     segs))))))]
    (future
      (try
        (loop []
          (when-not (and (realized? out-f) (realized? err-f))
            (Thread/sleep 40)
            (flush!)
            (recur)))
        (let [code (:exit @proc)]
          (flush!)
          (on-exit {:code code
                    :stopped? @stopped
                    :ms (- (System/currentTimeMillis) t0)}))
        (catch Throwable t
          (on-exit {:code -1 :error (ex-message t)
                    :ms (- (System/currentTimeMillis) t0)}))))
    {:proc proc :writer writer :stopped stopped}))

(defn send!
  "Writes a line to the program's stdin."
  [{:keys [^Writer writer]} line]
  (try (.write writer (str line "\n"))
       (.flush writer)
       true
       (catch Exception _ false)))

(defn close-input!
  "End of file on stdin: what Ctrl+D does in a terminal."
  [{:keys [^Writer writer]}]
  (try (.close writer) (catch Exception _ nil)))

(defn stop! [{:keys [proc stopped]}]
  (reset! stopped true)
  (try (p/destroy-tree proc) (catch Exception _ nil)))

(defn alive? [{:keys [proc]}]
  (and proc (p/alive? proc)))
