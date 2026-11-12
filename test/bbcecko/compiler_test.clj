(ns bbcecko.compiler-test
  (:require [babashka.fs :as fs]
            [bbcecko.compiler :as c]
            [clojure.test :refer [deftest is testing]]))

(def gcc-output
  "bad.cpp: In function ‘int main()’:
bad.cpp:6:5: error: expected ‘,’ or ‘;’ before ‘cout’
bad.cpp:7:27: warning: comparison of integer expressions of different signedness: ‘int’ and ‘unsigned int’ [-Wsign-compare]
bad.cpp:9:5: error: ‘y’ was not declared in this scope
bad.cpp:3:5: note: suggested alternative: ‘x’
/usr/bin/ld: /tmp/cc.o: in function `main':
bad.cpp:(.text+0x41): undefined reference to `foo()'
bad.cpp:(.text+0x55): undefined reference to `foo()'
collect2: error: ld returned 1 exit status")

(deftest parses-gcc
  (let [ds (c/parse-diagnostics gcc-output)]
    (is (= 4 (count ds)) "two errors, a warning, one linker error (deduplicated)")
    (is (= {:file "bad.cpp" :line 6 :col 5 :severity :error :tip :tip/missing-semicolon}
           (select-keys (first ds) [:file :line :col :severity :tip])))
    (is (= :warning (:severity (second ds))))
    (testing "a note attaches to the diagnostic before it"
      (is (= ["suggested alternative: ‘x’"] (:notes (nth ds 2)))))
    (is (= :tip/undeclared (:tip (nth ds 2))))
    (testing "linker errors have no line"
      (is (= {:line nil :linker? true :tip :tip/undefined-ref}
             (select-keys (nth ds 3) [:line :linker? :tip]))))))

(deftest parses-clang
  (let [ds (c/parse-diagnostics "x.cpp:4:12: error: use of undeclared identifier 'y'\n")]
    (is (= [{:file "x.cpp" :line 4 :col 12 :severity :error
             :message "use of undeclared identifier 'y'" :tip :tip/undeclared}]
           ds))))

(deftest install-hints
  (is (= "sudo apt install build-essential" (:cmd (c/install-hint {"ID" "ubuntu"}))))
  (is (= "sudo dnf install gcc-c++" (:cmd (c/install-hint {"ID" "fedora"}))))
  (is (= "sudo pacman -S gcc" (:cmd (c/install-hint {"ID" "manjaro" "ID_LIKE" "arch"}))))
  (is (= "sudo apt install build-essential" (:cmd (c/install-hint {"ID" "pop" "ID_LIKE" "ubuntu debian"})))))

(deftest compiles-for-real
  (when (= :ok (:status (c/detect)))
    (let [dir (str (fs/create-temp-dir))
          ok  (str (fs/path dir "ok.cpp"))
          bad (str (fs/path dir "bad.cpp"))]
      (spit ok "#include <iostream>\nint main(){std::cout<<1;}\n")
      (spit bad "int main() {\n  int x = 5\n  return x;\n}\n")
      (let [r (c/compile! (c/detect) dir ok)]
        (is (:ok? r))
        (is (fs/executable? (:out r))))
      (let [r (c/compile! (c/detect) dir bad)]
        (is (not (:ok? r)))
        (is (= 3 (:line (first (:diagnostics r)))))
        (is (= :tip/missing-semicolon (:tip (first (:diagnostics r))))))
      (fs/delete-tree dir))))

(deftest parses-apple-linker
  (let [ds (c/parse-diagnostics "Undefined symbols for architecture arm64:\n  \"foo()\", referenced from:\n      _main in x.o\nld: symbol(s) not found for architecture arm64\nclang++: error: linker command failed with exit code 1 (use -v to see invocation)\n")]
    (is (= [{:message "undefined reference to `foo()'" :linker? true :tip :tip/undefined-ref}]
           (map #(select-keys % [:message :linker? :tip]) ds)))))

(deftest compiles-with-clang
  (when-let [clang (some-> (fs/which "clang++") str)]
    (let [dir (str (fs/create-temp-dir))
          bad (str (fs/path dir "bad.cpp"))
          cc  {:status :ok :cxx clang :clang? true}]
      (spit bad "int main() {\n  int x = 5\n  return y;\n}\n")
      (let [{:keys [ok? diagnostics]} (c/compile! cc dir bad)]
        (is (not ok?))
        (is (every? :line diagnostics) "clang output parses into located errors")
        (is (= :tip/missing-semicolon (:tip (first diagnostics)))))
      (fs/delete-tree dir))))
