(ns bbcecko.runner-test
  (:require [babashka.fs :as fs]
            [bbcecko.compiler :as c]
            [bbcecko.runner :as r]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- build [dir name src]
  (let [p (str (fs/path dir name))]
    (spit p src)
    (:out (c/compile! (c/detect) dir p))))

(defn- collect [exe dir & {:keys [input wait stop-after]}]
  (let [out (atom []) done (promise)
        h (r/start! exe dir {:on-output #(swap! out into %) :on-exit #(deliver done %)})]
    (when wait (Thread/sleep wait))
    (doseq [line input] (r/send! h line))
    (when stop-after (Thread/sleep stop-after) (r/stop! h))
    {:exit (deref done 5000 :timeout) :out @out}))

(deftest interactive-program
  (when (= :ok (:status (c/detect)))
    (let [dir (str (fs/create-temp-dir))
          exe (build dir "sum.cpp" "#include <iostream>\nusing namespace std;\nint main(){int a,b; cout<<\"a: \"; cin>>a>>b; cout<<a+b<<endl; cerr<<\"e\"; return 2;}\n")
          {:keys [exit out]} (collect exe dir :wait 300 :input ["3 4"])]
      (is (= 2 (:code exit)))
      (is (= "a: 7\n" (apply str (map second (filter #(= :out (first %)) out)))))
      (is (= "e" (apply str (map second (filter #(= :err (first %)) out)))))
      (fs/delete-tree dir))))

(deftest prompt-shows-before-input
  (when (= :ok (:status (c/detect)))
    (let [dir (str (fs/create-temp-dir))
          exe (build dir "ask.cpp" "#include <iostream>\nint main(){int a; std::cout<<\"Zadej: \"; std::cin>>a; return 0;}\n")
          out (atom [])
          done (promise)
          h (r/start! exe dir {:on-output #(swap! out into %) :on-exit #(deliver done %)})]
      (Thread/sleep 400)
      (is (= [[:out "Zadej: "]] @out) "a prompt without a newline must not wait in a buffer")
      (r/send! h "1")
      (is (= 0 (:code (deref done 5000 :timeout))))
      (fs/delete-tree dir))))

(deftest endless-output-can-be-stopped
  (when (= :ok (:status (c/detect)))
    (let [dir (str (fs/create-temp-dir))
          exe (build dir "loop.cpp" "#include <iostream>\nint main(){long i=0; while(true) std::cout<<i++<<std::endl;}\n")
          {:keys [exit out]} (collect exe dir :stop-after 700)]
      (is (map? exit) "on-exit fires even after a flood")
      (is (:stopped? exit))
      (is (every? #(<= (count (second %)) (+ r/flush-cap 100)) out))
      (fs/delete-tree dir))))
