# democracyworks/kehaar

A Clojure library designed to pass messages between RabbitMQ and core.async.

[![Build Status](https://travis-ci.org/democracyworks/kehaar.svg?branch=master)](https://travis-ci.org/democracyworks/kehaar)

## Usage

Add `[democracyworks/kehaar "0.3.0"]` to your dependencies.

There are two ways to use Kehaar. Functions in `kehaar.core` are a
low-level interface to connect up Rabbit and core.async. Functions in
`kehaar.wire-up` use these low-level functions but also will do a lot
of the low-level RabbitMQ channel and queue management for you.

## High-level interface

```
(require '[kehaar.wire-up :as wire-up])
```

The patterns of services we use in Kraken fall into one of these
patterns:

* You want to listen for events on the events exchange. So you'll need
  to declare it first.

```
(let [ch (declare-events-exchange conn
                                  "events"
                                  "topic"
                                  (config :topics "events"))]
  ;; later, on exit, close ch
  (rmq/close ch))
```

* You want to connect to an external query-response service over
  RabbitMQ.

```
(let [ch (external-service-channel conn
                                   "service-works.service.process"
                                   (config :queues "service-works.service.process")
                                   process-channel)] ;; a core.async channel
  ;; later, on exit, close ch
  (rmq/close ch))
```

* You want to make an query-response service based on a handler.

```
(let [ch (incoming-service-handler conn
                                   "service-works.service.process"
                                   (config :queues "service-works.service.process")
                                   handler)] ;; a handler function
  ;; later, on exit, close ch
  (rmq/close ch))
```

* You want to listen for events on the events exchange. (First declare
  the exchange above, only do that once.)

```
(let [ch (incoming-events-channel conn
                          "my-service.events.create-something"
                          (config :queues "my-service.events.create-something")
                          "create-something"
                          create-something-events)] ;; events core.async channel
  ;; later, on exit, close ch
  (rmq/close ch))
```

* You want to send events on the events exchange. (First declare the
  exchange above, only do that once.)

```
(let [ch (outgoing-events-channel conn
                          "events"
                          "create-something"
                          create-something-events)] ;; events core.async channel
  ;; later, on exit, close ch
  (rmq/close ch))
```

## Low-level interface

### Passing messages from RabbitMQ to core.async

```clojure
(ns example
  (:require [core.async :as async]
            [kehaar.core :as k]))

(def messages-from-rabbit (async/chan))

(k/rabbit->async a-rabbit-channel
                 "watership"
                 messages-from-rabbit)
```

edn-encoded payloads on the "watership" queue will be decoded and
placed on the `messages-from-rabbit` channel for you to deal with as
you like.

### Passing messages from core.async to RabbitMQ

```clojure
(ns example
  (:require [core.async :as async]
            [kehaar.core :as k]))

(def outgoing-messages (async/chan))

(k/async->rabbit outgoing-messages
                 a-rabbit-channel
                 "updates")
```

All messages sent to the `outgoing-messages` channel will encoded as
edn and placed on the "updates" queue.

### Applying a function to all messages on a RabbitMQ queue and responding on the reply-to queue with a correlation ID.

```clojure
(ns example
  (:require [kehaar.core :as k]
            [langohr.consumers :as lc]))

(defn factorial [n]
  (reduce * 1 (range 1 (inc n))))

(k/responder a-rabbit-channel
             "get-factorial"
             factorial)
```

edn-encoded payloads on the "get-factorial" queue will be decoded and
passed to the `factorial` function and the result will be encoded as
edn and delivered to the reply-to queue with the correlation ID.

### Using core.async channels to enqueue and receive replies to a RabbitMQ queue

```clojure
(ns example
  (:require [kehaar.core :as k]
            [clojure.core.async :as async]))

(def factorial-ch (async/chan))
(def request-factorial (k/ch->response-fn factorial-ch))

(k/wire-up-service a-rabbit-channel
                   "get-factorial"
                   factorial-ch)
```

Calling `(request-factorial 5)` will return a core.async channel for
you to listen for the result from the "get-factorial"
queue. `wire-up-service` listens on `factorial-ch` for messages,
creates a response channel, sends a message to the "get-factorial"
queue with a correlation ID, listens on a reply-to queue, and finally
puts the response (edn-decoded, naturally) onto the response
channel. The reply-to queue must receive a reply within 1000ms,
otherwise it will close the response channel.

```clojure
(let [response-ch (request-factorial 5)]
  (async/<!! response-ch)) ;;=> 120
```

## License

Copyright © 2015 Democracy Works, Inc.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
