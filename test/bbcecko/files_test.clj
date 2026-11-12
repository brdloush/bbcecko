(ns bbcecko.files-test
  (:require [babashka.fs :as fs]
            [bbcecko.files :as f]
            [clojure.test :refer [deftest is testing]]))

(deftest listing
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "zeta"))
    (fs/create-dirs (fs/path dir "Alfa"))
    (fs/create-dirs (fs/path dir ".git"))
    (doseq [n ["b.cpp" "A.cpp" ".hidden" "prog.o" "a.out" "data.out"]]
      (spit (str (fs/path dir n)) ""))
    (is (= [["Alfa" true] ["zeta" true] ["A.cpp" false] ["b.cpp" false] ["data.out" false]]
           (map (juxt :name :dir?) (f/list-dir dir))))
    (fs/delete-tree dir)))

(deftest creating
  (let [dir (str (fs/create-temp-dir))]
    (testing "no extension means .cpp, with a hello world inside"
      (let [{:keys [path]} (f/create-file! dir "uloha1")]
        (is (= "uloha1.cpp" (str (fs/file-name path))))
        (is (re-find #"int main" (slurp path)))))
    (is (= :exists (:error (f/create-file! dir "uloha1.cpp"))))
    (is (= :bad-name (:error (f/create-file! dir "../x.cpp"))))
    (is (= :bad-name (:error (f/create-file! dir "  "))))
    (testing "other files start empty"
      (is (= "" (slurp (:path (f/create-file! dir "vstup.txt"))))))
    (is (fs/directory? (:path (f/create-folder! dir "cviceni"))))
    (fs/delete-tree dir)))

(deftest reading
  (let [dir (str (fs/create-temp-dir))
        txt (str (fs/path dir "a.cpp"))
        bin (str (fs/path dir "b.bin"))]
    (spit txt "int main()\r\n{\r\n}\r\n")
    (fs/write-bytes bin (byte-array [1 2 0 3]))
    (is (= {:text "int main()\n{\n}\n"} (f/read-text txt)) "CRLF from Windows becomes LF")
    (is (= {:error :binary} (f/read-text bin)))
    (fs/delete-tree dir)))

(deftest kinds
  (is (= :cpp (f/kind "x/main.cpp")))
  (is (= :h (f/kind "a.hpp")))
  (is (f/runnable? "a.c"))
  (is (not (f/runnable? "a.h"))))

(deftest trash-by-hand
  (let [dir   (str (fs/create-temp-dir))
        home  (str (fs/create-temp-dir))
        file  (str (fs/path dir "x.cpp"))]
    (spit file "int main(){}")
    (with-redefs [fs/home (constantly (fs/path home))]
      (testing "mac: into ~/.Trash"
        (is (f/trash-by-hand! file true))
        (is (not (fs/exists? file)))
        (is (fs/exists? (fs/path home ".Trash" "x.cpp"))))
      (testing "a second file of the same name does not overwrite the first"
        (spit file "2")
        (is (f/trash-by-hand! file true))
        (is (= "2" (slurp (str (fs/path home ".Trash" "x.2.cpp")))))))
    (fs/delete-tree dir)
    (fs/delete-tree home)))
