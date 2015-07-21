# democracyworks/kehaar

A Clojure library designed to pass messages between RabbitMQ and core.async.

[![Build Status](https://travis-ci.org/democracyworks/kehaar.svg?branch=master)](https://travis-ci.org/democracyworks/kehaar)

## Usage

Add `[democracyworks/kehaar "0.4.0"]` to your dependencies.

There are two ways to use Kehaar. Functions in `kehaar.core` are a
low-level interface to connect up Rabbit and core.async. Functions in
`kehaar.wire-up` use these low-level functions but also will do a lot
of the low-level RabbitMQ channel and queue management for you.

### High-level interface

```clojure
(require '[kehaar.wire-up :as wire-up])
```

Some typical patterns:

* You want to listen for events on the "events" exchange. So you'll
  need to declare it first.

```clojure
(let [ch (declare-events-exchange conn
                                  "events"
                                  "topic"
                                  (config :topics "events"))]
  ;; later, on exit, close ch
  (rmq/close ch))
```

* You want to connect to an external query-response service over
  RabbitMQ.

```clojure
(let [ch (external-service conn
                           "service-works.service.process"
                           process-channel)] ;; a core.async channel
  ;; later, on exit, close ch
  (rmq/close ch))
```

* You want to make a query-response service. Send requests to
  in-channel and get responses on out-channel (core.async channels).


```clojure
(let [ch (incoming-service conn
                           "service-works.service.process"
                           (config :queues "service-works.service.process")
                           in-channel
                           out-channel)]
  ;; later, on exit, close ch
  (rmq/close ch))
```

Later, you can add a handler to it like this:

```
(start-responder! in-channel out-channel handler-function)
```

* You want to listen for events on the events exchange. (First declare
  the exchange above, only do that once.)

```clojure
(let [ch (incoming-events-channel conn
                                  "my-service.events.create-something"
                                  (config :queues "my-service.events.create-something")
                                  "create-something"
                                  create-something-events ;; events core.async channel
                                  100)] ;; timeout
  ;; later, on exit, close ch
  (rmq/close ch))
```

Later, you can add an event handler like this:

```
(start-event-handler! in-channel handler-function)
```

* You want to send events on the events exchange. (First declare the
  exchange above, only do that once.)

```clojure
(let [ch (outgoing-events-channel conn
                                  "events"
                                  "create-something"
                                  create-something-events)] ;; events core.async channel
  ;; later, on exit, close ch
  (rmq/close ch))
```

### Low-level interface

#### Passing messages from RabbitMQ to core.async

```clojure
(ns example
  (:require [core.async :as async]
            [kehaar.core :as k]))

(def messages-from-rabbit (async/chan))

(k/rabbit=>async a-rabbit-channel
                 "watership"
                 messages-from-rabbit)
```

edn-encoded payloads on the "watership" queue will be decoded and
placed on the `messages-from-rabbit` channel for you to deal with as
you like. Each message has `:message` and `:metadata`.

#### Passing messages from core.async to RabbitMQ

```clojure
(ns example
  (:require [core.async :as async]
            [kehaar.core :as k]))

(def outgoing-messages (async/chan))

(k/async=>rabbit outgoing-messages
                 a-rabbit-channel
                 "updates")
```

All messages sent to the `outgoing-messages` channel will encoded as
edn and placed on the "updates" queue. Each message should have
`:message` and `:metadata`.

### Passing messages from core.async to RabbitMQ based on reply-to

```clojure
(ns example
  (:require [core.async :as async]
            [kehaar.core :as k]))

(def outgoing-messages (async/chan))

(k/async=>rabbit-with-reply-to outgoing-messages
                               a-rabbit-channel)
```

All messages sent to the `outgoing-messages` channel will ebe ncoded
as edn and placed on the queue specified in the `:reply-to` key in the
metadata. Each message should have `:message` and `:metadata`.

## License

Copyright © 2015 Democracy Works, Inc.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
