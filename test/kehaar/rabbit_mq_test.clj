(ns kehaar.rabbit-mq-test
  (:require [clojure.test :refer :all]
            [kehaar.core :refer :all]
            [clojure.core.async :as async]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]
            [langohr.basic :as lb]
            [langohr.consumers :as lc]))

(deftest ^:rabbit-mq connect-to-broker-test
  (testing "connects to running broker and returns connection"
    (is (= (class (connect-to-broker {}))
           com.novemberain.langohr.Connection)))
  (testing "returns nil when broker unavailable"
    (is (nil? (connect-to-broker {:host "localhost" :port 65535} 1)))))

(deftest ^:rabbit-mq async->rabbit-rabbit->async-test
  (let [conn (rmq/connect)
        ch (lch/open conn)
        rabbit-queue (lq/declare-server-named ch {:exclusive true})
        chan (async/chan)
        response-chan (async/chan)
        message {:test (java.util.UUID/randomUUID)}]
    (async->rabbit chan ch rabbit-queue)
    (rabbit->async ch rabbit-queue response-chan)
    (async/>!! chan message)
    (is (= message (async/<!! response-chan)))
    (Thread/sleep 500) ; wait for ack before closing channel
    (rmq/close ch)
    (rmq/close conn)))

(deftest ^:rabbit-mq wire-up-service-test
  (let [conn (rmq/connect)
        ch (lch/open conn)

        rabbit-queue (lq/declare-server-named ch {:exclusive true})
        chan (async/chan)
        response-fn (ch->response-fn chan)]

    (responder ch rabbit-queue str)
    (wire-up-service ch rabbit-queue chan)

    (let [message {:testing "wire-up"}
          response-chan (response-fn message)
          response (async/<!! response-chan)]
      (is (= response (str message))))

    (rmq/close ch)
    (rmq/close conn)))
