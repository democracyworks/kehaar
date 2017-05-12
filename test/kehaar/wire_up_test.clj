(ns kehaar.wire-up-test
  (:require [kehaar.wire-up :refer :all]
            [clojure.test :refer :all]
            [clojure.core.async :as async]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]
            [langohr.basic :as lb]
            [langohr.consumers :as lc]
            [clojure.tools.logging :as log]
            [kehaar.async :refer [bounded<!! bounded>!!]]
            [kehaar.test-config :refer [rmq-config]]))

(deftest ^:rabbit-mq events-test
  (testing "we can publish events and receive them, going through rabbit"
    (let [conn   (rmq/connect rmq-config)
          ch-out (async/chan 1000)
          ch-in  (async/chan 1000)
          chs [(declare-events-exchange conn "test-events" "topic" {})
               (incoming-events-channel conn "test-in" {} "test-events" "test-event" ch-in 1000)
               (outgoing-events-channel conn "test-events" "test-event" ch-out)]]
      (try
        (dotimes [x 1000]
          (let [x (java.util.UUID/randomUUID)]
            (bounded>!! ch-out {:hello! :there! :uuid x} 100)
            (is (= {:hello! :there! :uuid x} (bounded<!! ch-in 100)))))
        (finally
          (async/close! ch-in)
          (async/close! ch-out)
          (doseq [ch chs]
            (rmq/close ch))
          (rmq/close conn)))))

  (testing "we can publish a bunch of events and then receive them
  later, going through rabbit"
    (let [conn   (rmq/connect rmq-config)
          ch-out (async/chan 1000)
          ch-in  (async/chan 1000)
          chs [(declare-events-exchange conn "test-events" "topic" {})
               (incoming-events-channel conn "test-in" {} "test-events" "test-event" ch-in 1000)
               (outgoing-events-channel conn "test-events" "test-event" ch-out)]]
      (try
        (let [messages (for [x (range 1000)
                             :let [x (java.util.UUID/randomUUID)]]
                         {:hello! x})]
          (doseq [message messages]
            (bounded>!! ch-out message 100))
          (doseq [message messages]
            (is (= message (bounded<!! ch-in 100)))))
        (finally
          (async/close! ch-in)
          (async/close! ch-out)
          (doseq [ch chs]
            (rmq/close ch))
          (rmq/close conn)))))

  (testing "we can set up a handler function on incoming events"
    (let [conn   (rmq/connect rmq-config)
          ch-out (async/chan 1000)
          ch-in  (async/chan 1000)
          test-chan (async/chan 1)
          chs [(declare-events-exchange conn "test-events" "topic" {})
               (incoming-events-channel conn "test-in" {} "test-events" "test-event" ch-in 1000)
               (outgoing-events-channel conn "test-events" "test-event" ch-out)]]
      (try
        (start-event-handler! ch-in
                              (fn [message]
                                (bounded>!! test-chan :hello 100))
                              2)
        (bounded>!! ch-out :hello 100)
        (is (= :hello (bounded<!! test-chan 100)))
        (finally
          (async/close! ch-in)
          (async/close! ch-out)
          (doseq [ch chs]
            (rmq/close ch))
          (rmq/close conn))))))

(deftest event-handler-test
  (testing "our event handler fires"
    (let [in (async/chan)
          out (async/chan)]
      (try
        (start-event-handler! in (fn [_] (async/put! out 1)) 2)
        (async/put! in 100)
        (is (= 1 (bounded<!! out 100)))
        (finally
          (async/close! in)
          (async/close! out))))))

(deftest simple-service-test
  (testing "we can return a value"
    (let [in (async/chan 1)
          out (async/chan 1)
          service-fn (fn [{:keys [n]}]
                       (* 10 n))
          metadata {:dude 100}]
      (try
        (start-responder! in out service-fn 1)
        (bounded>!! in {:message {:n 20}
                        :metadata metadata} 100)
        (is (= {:message 200
                :metadata metadata} (bounded<!! out 100)))
        (finally (async/close! out)
                 (async/close! in)))))

  (testing "we can return nil"
    (let [in (async/chan 1)
          out (async/chan 1)
          service-fn (fn [_]
                       nil)
          metadata {:dude 100}]
      (try
        (start-responder! in out service-fn 1)
        (bounded>!! in {:message {:n 20}
                        :metadata metadata} 100)
        (is (nil? (:message (bounded<!! out 100))))
        (finally (async/close! out)
                 (async/close! in)))))

  (testing "we can return a channel"
    (let [in (async/chan 1)
          out (async/chan 1)
          service-fn (fn [{:keys [n]}]
                       (async/go (* 10 n)))
          metadata {:dude 100}]
      (try
        (start-responder! in out service-fn 1)
        (bounded>!! in {:message {:n 20}
                        :metadata metadata} 100)
        (is (= {:message 200
                :metadata metadata} (bounded<!! out 100)))
        (finally (async/close! out)
                 (async/close! in))))))

(deftest ^:rabbit-mq service-test
  (testing "create an incoming service and an outgoing services that
  calls it, roundtripping through rabbit."
    (let [conn   (rmq/connect rmq-config)
          ch-ext (async/chan 1000)
          ch-in  (async/chan 1000)
          ch-out  (async/chan 1000)
          test-chan (async/chan 1)
          chs [(incoming-service conn "this.is.my.service"
                                 {:exclusive false :durable false :auto-delete true}
                                 ch-in ch-out)
               (external-service conn "" "this.is.my.service"
                                 {:exclusive false :durable false :auto-delete true}
                                 1000 ch-ext)]
          f (async->fn ch-ext)]
      (try
        (start-responder! ch-in ch-out
                          (fn [message]
                            (log/debug "I am here!")
                            {:answer (* 100 (:n message))})
                          1)
        (is (= {:answer 3400} (bounded<!! (f {:n 34}) 1000)))
        (finally
          (async/close! ch-in)
          (async/close! ch-ext)
          (async/close! ch-out)
          (doseq [ch chs]
            (rmq/close ch))
          (rmq/close conn))))))

(deftest async->fn-test
  (testing "async->fn structure"
    (let [c (async/chan 1) ;; we need buffered channels for external services
          response-fn (async->fn c)
          message {:test true}
          response-channel (response-fn message)]
      (is (= [response-channel message] (async/<!! c))))))

(deftest ^:rabbit-mq async->fn-rmq-test
  (testing "response is nil when no response to service past timeout"
    (let [timeout   2000
          conn      (rmq/connect rmq-config)
          ch-async  (async/chan 1000)
          ch-rabbit (external-service
                     conn "" "this.is.my.service"
                     {:exclusive false :durable false :auto-delete true}
                     timeout ch-async)
          f         (async->fn ch-async)]
      (try
        (let [start    (System/currentTimeMillis)
              response (async/<!! (f {}))
              stop     (System/currentTimeMillis)
              duration (- stop start)]
          ;; response channel should return nil when timeout occurs
          (is (= nil (async/<!! (f {}))))
          ;; test that it took longer than the timeout
          (is (>= duration timeout)))
        (finally
          (async/close! ch-async)
          (rmq/close ch-rabbit)
          (rmq/close conn))))))

(deftest async->fire-and-forget-fn-test
  (testing "async->fire-and-forget-fn structure"
    (let [c (async/chan 1) ; we need buffered channels for external services
          send-fn (async->fire-and-forget-fn c)
          message {:test true}]
      (send-fn message) ; we need buffered channels for external services
      (is (= {:metadata {} :message message} (async/<!! c)))))
  (testing "async->fire-and-forget-fn custom metadata"
    (let [c (async/chan 1)
          metadata {:anything :can-go-here}
          send-fn (async->fire-and-forget-fn c metadata)
          message {:test true}]
      (send-fn message)
      (is (= {:metadata metadata :message message} (async/<!! c))))))

(deftest ^:rabbit-mq start-streaming-responder!-test
  (testing "streams responses on the out channel if size is below threshold"
    (let [in-ch (async/chan 1)
          out-ch (async/chan 10)
          conn (rmq/connect rmq-config)]
      (start-streaming-responder! conn in-ch out-ch range 10 1)
      (async/>!! in-ch {:message 4})
      (testing "sends the responses on that channel")
      (is (= 0 (:message (async/<!! out-ch))))
      (is (= 1 (:message (async/<!! out-ch))))
      (is (= 2 (:message (async/<!! out-ch))))
      (is (= 3 (:message (async/<!! out-ch))))))
  (testing "streams responses on a bespoke queue once size is above threshold"
    (let [in-ch (async/chan 1)
          out-ch (async/chan 10)
          conn (rmq/connect rmq-config)]
      (start-streaming-responder! conn in-ch out-ch range 10 1)
      (async/>!! in-ch {:message 100})
      (testing "sends the first `threshold` messages on the out channel"
        (doseq [n (range 10)]
          (is (= n (:message (async/<!! out-ch))))))
      (testing "then sends a message pointing to a bespoke queue"
        (let [bespoke-response (async/<!! out-ch)
              rabbit-queue (get-in bespoke-response [:message :kehaar.core/response-queue])]
          (is rabbit-queue)
          (testing "then sends the rest of the messages on the bespoke queue"
            (doseq [n (range 10 100)]
              (is (= (str n) (-> conn
                                 lch/open
                                 (lb/get rabbit-queue)
                                 (nth 1)
                                 (String. "UTF-8"))))))
          (testing "then sends stop"
            (is (= ":kehaar.core/stop"
                   (-> conn
                       lch/open
                       (lb/get rabbit-queue)
                       (nth 1)
                       (String. "UTF-8"))))))))))
