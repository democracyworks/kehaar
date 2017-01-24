(ns kehaar-example.streaming.producer
  (:require [kehaar.rabbitmq :as kehaar-rabbit]
            [kehaar.configured :as configured]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async]))

(defn countdown [{:keys [num delay] :or {delay 0}}]
  (log/info "Counting down from" num)
  (let [c (async/chan 10)]
    (async/go-loop [n num]
      (log/info "Counting down from" n)
      (if (> 0 n)
        (async/close! c)
        (do
          (async/<! (async/timeout delay))
          (async/>! c {:num n
                       :delay delay})
          (recur (dec n)))))
    c))

(defn -main [& args]
  (log/info "Producer starting up...")
  (let [connection (kehaar-rabbit/connect-with-retries)]
    (configured/init!
     connection
     {:incoming-services
      [{:response :streaming
        :queue "countdown"
        :f 'kehaar-example.streaming.producer/countdown
        :threshold 5}]}))
  (log/info "Producer ready!")
  (.join (Thread/currentThread)))
