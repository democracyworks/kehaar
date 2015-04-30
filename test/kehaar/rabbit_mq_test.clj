(ns kehaar.rabbit-mq-test
  (:require [clojure.test :refer :all]
            [kehaar :refer :all]
            [clojure.core.async :as async]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]
            [langohr.basic :as lb]
            [langohr.consumers :as lc]))

(deftest ^:rabbit-mq async->rabbit-rabbit->async-test
  (let [conn (rmq/connect)
        ch (lch/open conn)
        rabbit-queue (lq/declare-server-named ch {:exclusive true})
        chan (async/chan)
        response-chan (async/chan)
        message {:test (java.util.UUID/randomUUID)}]
    (async->rabbit chan ch rabbit-queue)
    (lc/subscribe ch rabbit-queue (rabbit->async response-chan) {:auto-ack true})
    (async/>!! chan message)
    (is (= message (async/<!! response-chan)))

    (rmq/close ch)
    (rmq/close conn)))

(deftest ^:rabbit-mq wire-up-service-test
  (let [conn (rmq/connect)
        ch (lch/open conn)

        rabbit-queue (lq/declare-server-named ch {:exclusive true})
        chan (async/chan)
        response-fn (ch->response-fn chan)]

    (lc/subscribe ch rabbit-queue (simple-responder str))
    (wire-up-service ch rabbit-queue chan)

    (let [message {:testing "wire-up"}
          response-chan (response-fn message)
          response (async/<!! response-chan)]
      (is (= response (str message))))

    (rmq/close ch)
    (rmq/close conn)))
