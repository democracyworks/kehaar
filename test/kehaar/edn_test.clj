(ns kehaar.edn-test
  (:require
   [clojure.test :refer :all]
   [kehaar.edn :refer :all])
  (:refer-clojure :exclude [pr-str read-string]))

(deftest sanitize-test
  (testing "replaces all nested regexen with string representations"
    (is (= {:foo {:bar "re0" :baz "stringy"}
            :qux #{"re1" [:hi "re2"]}
            :quux {:quuz "re3" :corge {"grault" "re4"}}}
           (sanitize {:foo {:bar #"re0" :baz "stringy"}
                      :qux #{#"re1" [:hi #"re2"]}
                      :quux {:quuz #"re3" :corge {"grault" #"re4"}}})))))

(deftest pr-str-test
  (testing "generates valid EDN from data structures containing regexen"
    (let [input {:foo {:bar #"re0" :baz "stringy"}
                 :qux #{#"re1" [:hi #"re2"]}
                 :quux {:quuz #"re3" :corge {"grault" #"re4"}}}
          expected {:foo {:bar "re0" :baz "stringy"}
                    :qux #{"re1" [:hi "re2"]}
                    :quux {:quuz "re3" :corge {"grault" "re4"}}}]
      (is (= expected
             (-> input
                 pr-str
                 read-string))))))

(deftest read-string-test
  (testing "handles unknown tags by just dropping their values into result"
    (let [input "#{#foo \"tagged foo\" #bar {:guess \"I'm a map\"} #qux [1 2 3]}"
          expected #{"tagged foo" {:guess "I'm a map"} [1 2 3]}]
      (is (= expected
             (read-string input))))))
