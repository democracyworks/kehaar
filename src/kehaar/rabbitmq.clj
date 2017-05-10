(ns kehaar.rabbitmq
  (:require [langohr.core :refer [connect]]
            [clojure.tools.logging :as log])
  (:import (java.net ConnectException)
           (java.util.concurrent TimeoutException)))

(defn connect-with-retries
  "Attempts to connect to RabbitMQ broker `tries` times.
  Returns connection if successful."
  ([] (connect-with-retries {}))
  ([config] (connect-with-retries config 5))
  ([config tries]
   (loop [attempt 1]
     (if-let [connection (try
                           (connect config)
                           (catch ConnectException ce
                             (log/warn "RabbitMQ not available:" (.getMessage ce) "attempt:" attempt)
                             (when (>= attempt tries)
                               (throw ce)))
                           (catch TimeoutException te
                             (log/warn "Timeout trying to connect to RabbitMQ:" (.getMessage te) "attempt:" attempt)
                             (when (>= attempt tries)
                               (throw te))))]
       (do (log/info "RabbitMQ connected.")
           connection)
       (do
         (Thread/sleep (* attempt 1000))
         (recur (inc attempt)))))))
