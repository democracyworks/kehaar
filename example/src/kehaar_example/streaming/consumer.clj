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
      (let [return-ch (get-countdown n)]
        (loop []
          (when-let [v (async/<!! return-ch)]
            (log/info "Got " v)
            (recur)))))
    (System/exit 0)))
