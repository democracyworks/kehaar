(ns kehaar.jobs
  (:require [clojure.core.async :as async]
            [langohr.basic :as lb]))

(def ^:const kehaar-exchange "kehaar")

(def jobs (atom {}))

(def workers-chan (async/chan))
(def workers-chan-initialized? (atom false))

(defn new-routing-key []
  (str "kehaar-job." (java.util.UUID/randomUUID)))

(defn async->job
  "Like wire-up/async->fire-and-forget-fn, returns a function that
  sends the appropriate message on the channel so it gets routed to
  the appropriate service. Except it also takes a function to call
  every time an event comes through. Returns the job's routing-key."
  [chan]
  (fn [message handler]
    (let [routing-key (new-routing-key)]
      (swap! jobs assoc routing-key {:handler handler
                                     :workers 1})
      (async/>!! chan
                 {:message
                  {::routing-key routing-key
                   ::message message}})
      routing-key)))

(defn async->subjob
  "Like async->job, but it's a 2-arity function that takes the
  routing-key as an arg."
  [chan]
  (fn [routing-key message]
    (async/>!! chan
               {:message
                {::routing-key routing-key
                 ::message message}})
    (async/>!! workers-chan
               {:message
                {::routing-key routing-key}})))

(defn async=>rabbit
  "A jobs-specific async=>rabbit for job handlers."
  [chan rabbit-ch exchange routing-key]
  (async/go-loop []
    (let [{:keys [:message :metadata] :as ch-message} (async/<! chan)]
      (if (nil? ch-message)
        (lb/publish rabbit-ch
                    exchange
                    routing-key
                    (pr-str {::message ::complete
                             ::routing-key routing-key})
                    {})
        (do
          (lb/publish rabbit-ch
                      exchange
                      routing-key
                      (pr-str message)
                      metadata)
          (recur))))))

(defn cancel!
  "Cancel a job by its routing key."
  [routing-key]
  (swap! jobs dissoc routing-key))

(defn complete!
  "Record a completed worker."
  [routing-key]
  (swap! jobs update-in [routing-key :workers] dec))

(defn add-worker!
  "Record a new worker."
  [routing-key]
  (swap! jobs update-in [routing-key :workers] inc))
