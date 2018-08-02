(ns kehaar.configured-test
  (:require [clojure.test :refer :all]
            [kehaar.configured :refer :all]))

(deftest realize-symbol-or-self-test
  (testing "with a var"
    (is (= inc (realize-symbol-or-self inc))))
  (testing "with a symbol"
    (is (= inc (realize-symbol-or-self 'clojure.core/inc)))
    (testing "can resolve something in a ns not yet required"
      (is (= :bar (realize-symbol-or-self 'kehaar.not-yet-required/foo))))
    (testing "throws when symbol can't be resolved"
      (is (thrown? Exception (realize-symbol-or-self 'not.a.real.ns/bad)))
      (is (thrown? Exception (realize-symbol-or-self 'clojure.core/NOPE))))))
