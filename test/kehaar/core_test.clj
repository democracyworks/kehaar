(ns kehaar.core-test
  (:require [clojure.test :refer :all]
            [kehaar.core :refer :all]
            [clojure.core.async :as async]
            [kehaar.async :refer [bounded<!! bounded>!!]])
  (:import [java.util.concurrent Executors]))

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
      payload (edn-bytes message)
      bad-payload (byte-array (map int "{"))
      nil-payload (edn-bytes nil)]

  (deftest rabbit->async-handler-fn-test
    (let [c (async/chan 1)
          handler (channel-handler c "" 100)]
      (testing "only passes through the edn-decoded payload"
        (handler rabbit-ch metadata payload)
        (let [returned-message (bounded<!! c 100)]
          (is (= message (:message returned-message)))))
      (testing "returns the delivery-tag metadata"
        (is (= [:ack (:delivery-tag metadata)]
               (handler rabbit-ch metadata payload)))
        (bounded<!! c 100))

      (testing "non-edn payload returns nack and puts nothing on channel"
        (is (= [:nack (:delivery-tag metadata) false]
               (handler rabbit-ch metadata bad-payload)))
        (async/alt!!
          c (is false "Bad payload should put nothing on channel.")
          (async/timeout 100) (is true)))

      (testing "nil payload returns nack and puts nothing on channel"
        (is (= [:nack (:delivery-tag metadata) false]
               (handler rabbit-ch metadata nil-payload)))
        (async/alt!!
          c (is false "Bad payload should put nothing on channel.")
          (async/timeout 100) (is true))))))


(deftest responder-fn-test
  (testing "can return a regular value"
    (let [c (async/chan 1)
          f (responder-fn c (fn [message]
                              (* message 10)))]
      (f {:message 10
          :metadata :dude!})
      (is (= {:message 100
              :metadata :dude!}
             (bounded<!! c 100)))))
  (testing "can return a channel that will have the value"
    (let [c (async/chan 1)
          f (responder-fn c (fn [message]
                              (async/go
                                (* message 10))))]
      (f {:message 10
          :metadata :dude!})
      (is (= {:message 100
              :metadata :dude!}
             (bounded<!! c 100))))))

(deftest thread-handler-test
  (testing "Carries on in the face of exceptions thrown by f"
    (let [thread-pool (Executors/newFixedThreadPool 1)
          channel (async/chan)
          results-channel (async/chan 3)
          f (fn [n]
              (async/>!! results-channel (/ 1 n)))]
      (thread-handler channel f thread-pool)
      (async/>!! channel 2)
      (async/>!! channel 0)
      (async/>!! channel 3)
      (is (= (/ 1 2) (async/<!! results-channel)))
      (is (= (/ 1 3) (async/<!! results-channel))))))
