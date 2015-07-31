# democracyworks/kehaar

A Clojure library designed to pass messages between RabbitMQ and core.async.

[![Build Status](https://travis-ci.org/democracyworks/kehaar.svg?branch=master)](https://travis-ci.org/democracyworks/kehaar)

## Usage

Add `[democracyworks/kehaar "0.5.0"]` to your dependencies.

There are two ways to use Kehaar. Functions in `kehaar.core` are a
low-level interface to connect up Rabbit and core.async. Functions in
`kehaar.wire-up` use these low-level functions but also will do a lot
of the low-level RabbitMQ channel and queue management for you.

In most cases, `kehaar.wire-up` should be all you need to set up
services and events.

### High-level interface

See the example project for more detail.

```clojure
(require '[kehaar.wire-up :as wire-up])
```

Some typical patterns:

* You want to listen for events on the "events" exchange. So you'll
  need to declare it first.

```clojure
(let [ch (wire-up/declare-events-exchange conn
                                          "events"
                                          "topic"
                                          (config [:topics "events"]))]
  ;; later, on exit, close ch
  (rmq/close ch))
```

* You want to connect to an external query-response service over
  RabbitMQ.

```clojure
(let [ch (wire-up/external-service conn
                                   "service-works.service.process"
                                   process-channel)] ;; a core.async channel
  ;; later, on exit, close ch
  (rmq/close ch))
```

Then you can create a function that "calls" that service, like so:

```clojure
(def process (wire-up/async->fn process-channel))
```

The returned function, when called, will return a core.async channel
that will eventually contain the result. If you're chaining up
services, this channel can be returned from a handler as well, letting
you chain async calls. The returned function takes a single argument,
which must be some edenizable value (including `nil`).

* You want to make a query-response service. Send requests to
  in-channel and get responses on out-channel (core.async channels).


```clojure
(let [ch (wire-up/incoming-service conn
                                   "service-works.service.process"
                                   (config [:queues "service-works.service.process"])
                                   in-channel
                                   out-channel)]
  ;; later, on exit, close ch
  (rmq/close ch))
```

Later, you can add a handler to it like this:

```clojure
(wire-up/start-responder! in-channel out-channel handler-function)
```

Some notes:

`handler-function` should be a function of a single argument. It
accepts messages from the queue, which had been serialized and
deserialized as EDN. So expect any kind of serializable value,
including `nil`.

You can call `wire-up/start-responder!` multiple times to start
different threads running the same handler.

Incoming messages are nacked if the thread is taking too long to
process the messages. This allows different instances of the service
to process those messages.

`in-channel` should be an unbuffered channel. `out-channel` should
have a large buffer to get messages out to RabbitMQ as soon as
possible.

* You want to listen for events on the events exchange. (First declare
  the exchange above, only do that once.)

```clojure
(let [ch (wire-up/incoming-events-channel conn
                                          "my-service.events.create-something"
                                          (config [:queues "my-service.events.create-something"])
                                          "events"
                                          "create-something"
                                          create-something-events ;; events core.async channel
                                          100)] ;; timeout
  ;; later, on exit, close ch
  (rmq/close ch))
```

Later, you can add an event handler like this:

```
(wire-up/start-event-handler! in-channel handler-function)
```

Some notes:

`in-channel` should be unbuffered.

`handler-function` is a function of exactly one argument, which is the
message that was passed down the rabbit hole. It's serialized on the
way to a ByteString using EDN, so expect some data that can be
edenized. The return can be any edenizable value (including `nil`) OR
a core.async channel that will include the result (also must be
edenizable). That second option lets you maintain asynchrony because
other services using kehaar are doing the same.

Each call to `wire-up/start-event-handler!` creates a new thread.

Incoming messages are nacked if the thread is taking too long to
process the messages. This allows different instances of the service
to process those messages.

* You want to send events on the events exchange. (First declare the
  exchange above, only do that once.)

```clojure
(let [ch (wire-up/outgoing-events-channel conn
                                          "events"
                                          "create-something"
                                          create-something-events)] ;; events core.async channel
  ;; later, on exit, close ch
  (rmq/close ch))
```

The event messages you send on the channel should be edenizable.

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

## Backpressure

Kehaar implements backpressure now using RabbitMQ nacks and
requeuing. Messages that don't parse will be nacked without requeing.

Here's the thing to know: each `wire-up/start-responder!` and
`wire-up/start-event-handler!` starts a new thread. When the
in-channel of either of those is full (meaning it takes more than
100ms to add to the core.async channel), the incoming message is
nacked and requeued.

You can start multiple threads with the same handler by calling
`wire-up/start-responder!` and `wire-up/start-event-handler!` multiple
times.

### Connecting to RabbitMQ

While it is perfectly acceptable to connect to RabbitMQ using langohr
directly, there is also `kehaar.rabbitmq/connect-with-retries`. This
fn takes a RabbitMQ config map just like `langohr.core/connect` and,
optionally, a max-retry count (which defaults to 5 if ommitted).

It then attempts to connect to the RabbitMQ broker up to the max-retry
count with backoff of `(* attempts 1000)` milliseconds.

If it fails to connect to the broker after hitting the maximum number
of retries, it will re-throw the final `java.net.ConnectException`
from `langohr.core/connect`.  If it succeeds, it will return the
connection just like `langohr.core/connect`.

## License

Copyright © 2015 Democracy Works, Inc.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
