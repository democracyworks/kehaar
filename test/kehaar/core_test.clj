(ns kehaar.core-test
  (:require [clojure.test :refer :all]
            [kehaar.core :refer :all]
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
      metadata {:year 2015 :delivery-tag 8675309}
      payload (edn-bytes message)]

  (deftest rabbit->async-handler-fn-test
    (let [c (async/chan 1)
          handler (rabbit->async-handler-fn c)]
      (testing "only passes through the edn-decoded payload"
        (handler rabbit-ch metadata payload)
        (let [returned-message (async/<!! c)]
          (is (= returned-message message))))
      (testing "returns the delivery-tag metadata"
        (is (= (:delivery-tag metadata)
               (handler rabbit-ch metadata payload)))
        (async/<!! c)))))

(deftest ch->response-fn-test
  (let [c (async/chan)
        response-fn (ch->response-fn c)
        message {:test true}
        response-channel (response-fn message)]
    (is (= [response-channel message] (async/<!! c)))))
