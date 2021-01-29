(ns dxpb-clj.core-test
  (:require [clojure.test :refer :all]
            [dxpb-clj.core :refer :all]))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 1 1))))

(def gcc-sample-dump (slurp "test-data/gcc-sample-dump.txt"))
(def gcc-sample-dump-parsed (slurp "test-data/gcc-sample-dump-parsed.edn"))

(deftest parse-dbulk-dump-test
  (testing "Result for sample gcc dump is right"
    (is (= (set (dbulk-dump-str->list-of-packages gcc-sample-dump))
           (set (read-string gcc-sample-dump-parsed))))))
