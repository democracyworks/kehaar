(ns kehaar-example.streaming.consumer
  (:require [kehaar.rabbitmq :as kehaar-rabbit]
            [kehaar.wire-up :as wire-up]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(def outgoing-countdown-requests (async/chan 100))
(def get-countdown (wire-up/async->fn outgoing-countdown-requests))

(defn -main [& args]
  (log/info "Consumer starting up...")
  (let [connection (kehaar-rabbit/connect-with-retries)
        outgoing-service-ch (wire-up/streaming-external-service
                             connection
                             ""
                             "countdown"
                             {}
                             5000
                             outgoing-countdown-requests)]
    (log/info "Consumer making a request!")
    (doseq [n [10 10 3 3 10]]
      (let [return-ch (get-countdown {:num n})]
        (loop []
          (when-let [v (async/<!! return-ch)]
            (log/info "Got" v)
            (recur)))))
    (log/info "Consumer making a request!")
    (let [return-ch (get-countdown {:num 100 :delay 2000})]
      (dotimes [n 10]
        (when-let [v (async/<!! return-ch)]
          (log/info "Got" v)))
      (async/close! return-ch)
      (log/info "Closed return channel"))
    (log/info "Consumer making a request!")
    (let [return-ch (get-countdown {:num 4 :delay 3000})
          v (async/<!! return-ch)]
      (log/info "Got" v)
      (async/close! return-ch)
      (log/info "Closed return channel"))
    (System/exit 0)))
