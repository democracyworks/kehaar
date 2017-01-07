(ns kehaar-example.jobs.counter
  "This is a service that provides work for requested jobs. In this
  case, it replies to jobs from the instigator service and
  subcontracts some of its work to the threes service."
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [kehaar.configured :as kehaar]
            [kehaar.jobs :as jobs]
            [kehaar.rabbitmq :as kehaar-rabbit]))

(def three-subjob (async/chan))

(def subcontract-three (jobs/async->subjob three-subjob))

(defn job-handler [out-chan routing-key message]
  (log/info "Got job, routing-key:" routing-key)
  (dotimes [n 5]
    (if (= n 3)
      (subcontract-three routing-key n)
      (do
        (Thread/sleep (rand-int 2000))
        (async/>!! out-chan (str n "-" message)))))
  (async/close! out-chan))

(def config
  {:incoming-jobs [{:f 'kehaar-example.jobs.counter/job-handler
                    :queue "counting-job"}]
   :outgoing-jobs [{:jobs-chan 'kehaar-example.jobs.counter/three-subjob
                    :queue "do-fizz"}]})

(defn setup []
  (let [connection (kehaar-rabbit/connect-with-retries)]
    (kehaar/init! connection config)))

(defn -main [& args]
  (log/info "Starting counter")
  (setup)
  (log/info "Started")
  (.join (Thread/currentThread)))
