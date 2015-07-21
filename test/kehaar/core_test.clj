(ns kehaar.core-test
  (:require [clojure.test :refer :all]
            [kehaar.core :refer :all]
            [clojure.core.async :as async]
            [kehaar.async :refer [bounded<!! bounded>!!]]))

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

(deftest async->fn-test
  (let [c (async/chan 1) ;; we need buffered channels for external services
        response-fn (async->fn c)
        message {:test true}
        response-channel (response-fn message)]
    (is (= [response-channel message] (async/<!! c)))))

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
