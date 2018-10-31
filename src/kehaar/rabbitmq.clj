(ns kehaar.rabbitmq
  (:require [langohr.core :refer [connect]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn dissoc-blank-config-params-with-defaults
  "Takes a langohr RabbitMQ connection config map and returns a new one with
  all blank params with known-sane defaults dissoc'ed. This will allow the
  default to take over rather than using an invalid config."
  [config]
  (let [params-with-defaults #{:username :password :vhost :host :port
                               :requested-heartbeat :requested-channel-max
                               :connection-timeout :automatically-recover}]
    (reduce-kv (fn [c k v]
                 (if (and (params-with-defaults k)
                          (if (string? v)
                            (str/blank? v)
                            (nil? v)))
                   (dissoc c k)
                   c))
               config config)))

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
