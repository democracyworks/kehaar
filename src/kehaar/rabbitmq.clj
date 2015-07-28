(ns kehaar.rabbitmq
  (:require [langohr.core :refer [connect]]
            [clojure.tools.logging :as log]))

(defn connect-with-retries
  "Attempts to connect to RabbitMQ broker `tries` times.
  Returns connection if successful, nil if not."
  ([config] (connect-with-retries config 5))
  ([config tries]
   (loop [attempt 1]
     (if-let [connection (try
                           (connect config)
                           (catch Throwable t
                             (log/warn "RabbitMQ not available:" (.getMessage t) "attempt:" attempt)
                             nil))]
       (do (log/info "RabbitMQ connected.")
           connection)
       (when (< attempt tries)
         (Thread/sleep (* attempt 1000))
         (recur (inc attempt)))))))
