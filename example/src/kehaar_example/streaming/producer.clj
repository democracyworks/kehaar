(ns kehaar-example.streaming.producer
  (:require [kehaar.rabbitmq :as kehaar-rabbit]
            [kehaar.configured :as configured]
            [clojure.tools.logging :as log]))

(defn countdown [{:keys [num delay]}]
  (log/info "Counting down from" num)
  (when (>= num 0)
    (lazy-seq
     (Thread/sleep (or delay 0))
     (cons num (countdown {:num (dec num)
                           :delay delay})))))

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
  (loop []
    (recur)))
