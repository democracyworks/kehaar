(ns kehaar-example.streaming.producer
  (:require [kehaar.rabbitmq :as kehaar-rabbit]
            [kehaar.wire-up :as wire-up]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(def in-ch (async/chan))
(def out-ch (async/chan))

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

    (wire-up/incoming-service
     connection "countdown" {} in-ch out-ch)

    (wire-up/start-streaming-responder!
     connection in-ch out-ch countdown 5))
  (log/info "Producer ready!")
  (loop []
    (recur)))
