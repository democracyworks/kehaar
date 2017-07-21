(ns kehaar-example.jobs.threes
  "This is a service that provides work for requested jobs. In this
  case, it is called as a subjob from the counter service."
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [kehaar.configured :as kehaar]
            [kehaar.jobs :as jobs]
            [kehaar.rabbitmq :as kehaar-rabbit]))

(defn job-handler [out-chan routing-key message]
  (log/info "Got job, routing-key:" routing-key)
  (async/>!! out-chan :fizz)
  (async/close! out-chan))

(def config
  {:incoming-jobs [{:f job-handler
                    :queue "do-fizz"}]})

(defn setup []
  (let [connection (kehaar-rabbit/connect-with-retries)]
    (kehaar/init! connection config)))

(defn -main [& args]
  (log/info "Starting threes")
  (setup)
  (log/info "Started")
  (.join (Thread/currentThread)))
