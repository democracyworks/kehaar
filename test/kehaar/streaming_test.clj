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
