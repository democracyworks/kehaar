# democracyworks/kehaar

A Clojure library designed to pass messages between RabbitMQ and core.async.

[![Build Status](https://travis-ci.org/democracyworks/kehaar.svg?branch=master)](https://travis-ci.org/democracyworks/kehaar)

## Usage

Add `[democracyworks/kehaar "0.8.1"]` to your dependencies.

There are a few namespaces available for connecting core.async
channels to rabbitmq. `kehaar.core` is a low-level
interface. `kehaar.wire-up` is a level above. And finally,
`kehaar.configured` is above that.

In most cases, the `kehaar.configured` namespace and
`kehaar.wire-up/async->fn` should be all you need to set up services
and events.

### kehaar.configured

See the example project for working examples. See the function
docstrings for all available options.

#### Convenience

You can use `kehaar.configured/init!` to set up all the below examples
in one go by passing a map with the keys `:event-exchanges`,
`:incoming-services`, `:external-services`, `:incoming-events`, and
`:outgoing-events`, each with a sequence of the maps for each kind of
thing. The return value of which is a collection of all the
shutdownable resources created in the process, and it may be passed to
`kehaar.configured/shutdown!` to clean them all up.

#### Examples

Assuming that a rabbitmq connection is available as `connection`.

Some typical patterns:

##### You want to listen for events on the "events" exchange.

You'll need to declare it.

```clojure
(kehaar.configured/init-exchange!
 connection
 {:exchange "events"})
```

##### You want to connect to an external query-response service over RabbitMQ.

```clojure
(kehaar.configured/init-external-service!
 connection
 {:queue "service-works.service.process"
  :channel 'fully.qualified/process-channel})
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

Some notes:

* For "fire-and-forget" semantics, set `:response` to `false` in the
  options map.
* If the service provides a stream of responses for each request, set
  `:response` to `:streaming`.

##### You want to make a query-response service.

```clojure
(kehaar.configured/init-incoming-service!
 connection
 {:queue "service-works.service.process"
  :f 'fully.qualified/handler-function})
```

Some notes:

* `handler-function` should be a function of a single argument. It
  accepts messages from the queue, which had been serialized and
  deserialized as EDN. So expect any kind of serializable value,
  including `nil`.
* If `:response` is `:streaming`, then `handler-function` should
  return a sequence, which probably should be lazy. Each value in the
  sequence will be returned to the client in order.
* Incoming messages are nacked if the thread is taking too long to
  process the messages. This allows different instances of the service
  to process those messages.
* If `:response` is `nil`, kehaar will not log error messages when the
  incoming messages lack a reply-to queue. This can reduce log noise
  if `:response` is set to `nil` on the request side.

##### You want to listen for events on a topic.

```clojure
(kehaar.configured/init-incoming-event!
 connection
 {:queue "my-service.events.create-something"
  :exchange "events"
  :routing-key "create-something"
  :f 'fully.qualified/handler-function})
```

Some notes:
* `handler-function` is a function of exactly one argument, which is
  the message that was passed down the rabbit hole. It's serialized on
  the way to a ByteString using EDN, so only expect data that can be
  EDN-ized.

##### You want to send events on the events exchange.

```clojure
(kehaar.configured/init-outgoing-event!
 connection
 {:exchange "events"
  :routing-key "create-something"
  :channel 'fully.qualified/created-event-channel})
```

The event messages you send on the channel must be EDN-izable.

#### Cleanup

Each of the `kehaar.configured/init-` functions returns a map of
resources that you'll want to clean up. Those maps may be passed to
`kehaar.configured/shutdown-part!` to do that.

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

## Connecting to RabbitMQ

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

Copyright © 2016 Democracy Works, Inc.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
