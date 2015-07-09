(ns kehaar.wire-up
  (:require
   [langohr.queue]
   [langohr.channel]
   [langohr.exchange]
   [kehaar.core]))

(defn incoming-service-handler
  "Wire up a handler to a queue. The handler will receive kehaar
  messages as per `kehaar.core/responder`.

  Returns a langohr channel. Please close it on exit."
  [connection queue-name options handler]
  (let [ch (langohr.channel/open connection)]
    (langohr.queue/declare ch queue-name options)
    (kehaar.core/responder ch queue-name handler)
    ch))

(defn incoming-events
  "Wire up a channel that will receive incoming events that match
  `routing-key`.

  Returns a langohr channel. Please close it on exit."
  [connection queue-name options routing-key channel]
  (let [ch (langohr.channel/open connection)
        queue (:queue (langohr.queue/declare ch queue-name options))]
    (langohr.queue/bind ch queue "events" {:routing-key routing-key})
    (kehaar.core/rabbit->async ch queue channel)
    ch))

(defn external-service-channel
  "Wire up a channel to call an external service.

  Returns a langohr channel. Please close it on exit."
  [connection queue-name options channel]
  (let [ch (langohr.channel/open connection)]
    (langohr.queue/declare ch queue-name options)
    (kehaar.core/wire-up-service ch queue-name channel)
    ch))

(defn outgoing-events-channel
  "Wire up a queue listening to a channel for events.

  Returns a langohr channel. Please close it on exit."
  [connection topic-name routing-key channel]
  (let [ch (langohr.channel/open connection)]
    (kehaar.core/async->rabbit channel ch topic-name routing-key)
    ch))

(defn declare-events-exchange
  "Declare an events exchange.

  Returns a langohr channel. Please close it on exit."
  [connection name type options]
  (let [ch (langohr.channel/open connection)]
    (langohr.exchange/declare ch name type options)
    ch))
