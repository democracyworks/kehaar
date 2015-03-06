(ns democracyworks.kehaar-test
  (:require [clojure.test :refer :all]
            [democracyworks.kehaar :refer :all]
            [clojure.core.async :as async]))

(defn edn-bytes
  "Returns a byte array of the edn representation of x."
  [x]
  (->> x
       pr-str
       (map int)
       byte-array))

(let [message {:edn [1 2]
               :test true
               :moon-landing #inst "1969-07-20"}
      rabbit-ch :rabbit-channel
      metadata {:year 2015}
      payload (edn-bytes message)]

  (deftest pass-through-test
    (let [c (async/chan)
          handler (pass-through c)]
      (testing "passes through rabbit channel and metadata"
        (handler rabbit-ch metadata payload)
        (let [[returned-ch returned-metadata _]  (async/<!! c)]
          (is (= returned-ch rabbit-ch))
          (is (= returned-metadata metadata))))
      (testing "passes through the edn-decoded payload"
        (handler rabbit-ch metadata payload)
        (let [[_ _ returned-message] (async/<!! c)]
          (is (= returned-message message))))))

  (deftest simple-pass-through-test
    (let [c (async/chan)
          handler (simple-pass-through c)]
      (testing "only passes through the edn-decoded payload"
        (handler rabbit-ch metadata payload)
        (let [returned-message (async/<!! c)]
          (is (= returned-message message)))))))

(deftest ch->response-fn-test
  (let [c (async/chan)
        response-fn (ch->response-fn c)
        message {:test true}
        response-channel (response-fn message)]
    (is (= [response-channel message] (async/<!! c)))))
