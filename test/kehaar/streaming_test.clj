(ns kehaar.streaming-test
  (:require [clojure.test :refer [deftest is testing]]
            [kehaar.core :as k.core]
            [kehaar.configured :as configured]
            [kehaar.wire-up :as wire-up]
            [clojure.core.async :as async]
            [langohr.core :as rmq]
            [kehaar.test-config :refer [rmq-config]]))

(deftest ^:rabbit-mq streaming-test
  (let [conn (rmq/connect rmq-config)
        stream-ch (async/chan 1)
        call! (wire-up/async->fn stream-ch)
        state (configured/init! conn
                                {:incoming-services
                                 [{:queue "stream-test"
                                   :f #(range (:n %))
                                   :response :streaming
                                   :threads 1
                                   :threshold 10}]
                                 :external-services
                                 [{:queue "stream-test"
                                   :channel stream-ch
                                   :response :streaming
                                   :timeout 30000}]})]
    (try
      (testing "when streaming size is below the threshold"
        (is (= (range 8) (k.core/async->lazy-seq (call! {:n 8})))))
      (testing "when streaming size is at the threshold"
        (is (= (range 10) (k.core/async->lazy-seq (call! {:n 10})))))
      (testing "when streaming size is above the threshold"
        (is (= (range 100) (k.core/async->lazy-seq (call! {:n 100})))))
      (testing "with a consumer that is slow to start and a short response"
        (let [response (k.core/async->lazy-seq (call! {:n 5}))]
          (println "# sleeping 2 seconds...")
          (Thread/sleep 2000)
          (is (= (range 5) response))))
      (testing "with a consumer that is slow to start and a long response"
        (let [response (k.core/async->lazy-seq (call! {:n 20}))]
          (println "# sleeping 2 seconds...")
          (Thread/sleep 2000)
          (is (= (range 20) response))))
      (testing "with a slow consumer and a short response"
        (print "# slow consumer test (5 items)...") (flush)
        (let [response (->> (call! {:n 5})
                            k.core/async->lazy-seq
                            (map (fn [x] (print x) (flush) (Thread/sleep 1000) (print ".") (flush) x))
                            doall)]
          (println)
          (is (= (range 5) response))))
      (testing "with a slow consumer and a long response"
        (print "# slow consumer test (20 items)...")
        (let [response (->> (call! {:n 20})
                            k.core/async->lazy-seq
                            (map (fn [x] (print x) (flush) (Thread/sleep 1000) (print ".") (flush) x))
                            doall)]
          (println)
          (is (= (range 20) response))))
      (finally
        (configured/shutdown! state)
        (rmq/close conn)))))

(deftest ^:rabbit-mq streaming-recovery-test
  (let [kill-connection (fn [c]
                          ;; based on https://github.com/rabbitmq/rabbitmq-java-client/blob/329156821f7b5e023e1d422558503bfb03c3ba34/src/test/java/com/rabbitmq/client/test/functional/Heartbeat.java
                          (.. c getDelegate getDelegate (setHeartbeat 0))
                          (println "# sleeping for 3 seconds to trigger heartbeat failure")
                          (Thread/sleep 3100))
        ;; streaming responder
        responder-conn (rmq/connect (assoc rmq-config :requested-heartbeat 1))
        responder-state (configured/init! responder-conn
                                          {:incoming-services
                                           [{:queue "stream-test-2"
                                             :f #(range (:n %))
                                             :response :streaming
                                             :threads 1
                                             :threshold 10}]})
        ;; streaming caller/consumer
        consumer-conn (rmq/connect rmq-config)
        stream-ch (async/chan 1)
        call! (wire-up/async->fn stream-ch)
        consumer-state (configured/init! consumer-conn
                                         {:external-services
                                          [{:queue "stream-test-2"
                                            :channel stream-ch
                                            :response :streaming
                                            :timeout 30000}]})
        recovered-queues' (atom [])
        recovered-queues (promise)]
    ;; collect recovered queues
    (rmq/on-queue-recovery
     responder-conn
     (fn [old-name new-name]
       (swap! recovered-queues' conj new-name)))
    (rmq/on-recovery
     responder-conn
     (fn [_] (deliver recovered-queues @recovered-queues')))
    (try
      ;; stream a few responses long enough that they will create bespoke
      ;; response queues
      (-> (call! {:n 100}) k.core/async->lazy-seq doall)
      (-> (call! {:n 100}) k.core/async->lazy-seq doall)
      (-> (call! {:n 100}) k.core/async->lazy-seq doall)
      (-> (call! {:n 100}) k.core/async->lazy-seq doall)
      ;; kill the connection to trigger recovery
      (kill-connection responder-conn)
      ;; check to see that extra response queues were not created
      (println "# waiting up to 10 seconds for recovery to finish")
      (is (= ["stream-test-2"] (deref recovered-queues 10000 ::timeout))
          "Only the incoming-service queue was recovered")
      (finally
        ;; cleanup
        (configured/shutdown! responder-state)
        (configured/shutdown! consumer-state)
        (rmq/close responder-conn)
        (rmq/close consumer-conn)))))
