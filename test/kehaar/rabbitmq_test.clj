(ns kehaar.rabbitmq-test
  (:require [clojure.test :refer :all]
            [kehaar.rabbitmq :refer :all]))

(deftest dissoc-blank-config-params-with-defaults-test
  (testing "leaves non-known-good-default nil / blank params alone"
    (is (= {:p1 "", :p2 nil}
           (dissoc-blank-config-params-with-defaults {:p1 "", :p2 nil}))))
  (testing "handles non-string values"
    (is (= {:p1 123}
           (dissoc-blank-config-params-with-defaults {:p1 123}))))
  (testing "dissoc's nil values with known-good defaults"
    (is (= {:p2 nil}
           (dissoc-blank-config-params-with-defaults
            {:username nil, :p2 nil}))))
  (testing "dissoc's blank string values with known-good defaults"
    (is (= {:p2 nil}
           (dissoc-blank-config-params-with-defaults
            {:host "", :p2 nil})))))
