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
    (let [return-ch (get-countdown 4)]
      (when-let [v (async/<!! return-ch)]
        (log/info "Got " v)))))
