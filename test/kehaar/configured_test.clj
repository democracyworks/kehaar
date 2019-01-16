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

;; Now that we've tested 'kehaar.not-yet-required/foo, that namespace has been
;; required, so there's no sense in testing it again

(deftest realize-fn-test
  (testing "with a var"
    (is (= inc (realize-fn inc false))))
  (testing "with a symbol"
    (is (= inc (realize-fn 'clojure.core/inc false)))
    (testing "throws when symbol can't be resolved"
      (is (thrown? Exception (realize-fn 'not.a.real.ns/bad false)))
      (is (thrown? Exception (realize-fn 'clojure.core/NOPE false))))))

(deftest realize-fn-debug-test
  (testing "with a var"
    (is (= inc (realize-fn inc :debug))))
  (testing "with a symbol"
    ;; This returns a var -- a subtle difference from the non-debug version
    (is (= #'inc (realize-fn 'clojure.core/inc :debug)))
    (testing "throws when symbol can't be resolved"
      (is (thrown? Exception (realize-fn 'not.a.real.ns/bad :debug)))
      (is (thrown? Exception (realize-fn 'clojure.core/NOPE :debug))))))
