(ns kehaar.rabbitmq
  (:require [langohr.core :refer [connect]]
            [clojure.tools.logging :as log]))

(defn connect-with-retries
  "Attempts to connect to RabbitMQ broker `tries` times.
  Returns connection if successful."
  ([] (connect-with-retries {}))
  ([config] (connect-with-retries config 5))
  ([config tries]
   (loop [attempt 1]
     (if-let [connection (try
                           (connect config)
                           (catch Exception e
                             (log/warn "RabbitMQ not available:"
                                       (.getMessage e)
                                       "attempt:" attempt)
                             (when (>= attempt tries)
                               (throw e))))]
       (do (log/info "RabbitMQ connected.")
           connection)
       (do
         (Thread/sleep (* attempt 1000))
         (recur (inc attempt)))))))
