# democracyworks/kehaar

A Clojure library designed to pass messages between RabbitMQ and core.async.

[![Build Status](https://travis-ci.org/democracyworks/kehaar.svg?branch=master)](https://travis-ci.org/democracyworks/kehaar)

## Usage

Add `[democracyworks/kehaar "0.9.0"]` to your dependencies.

There are a few namespaces available for connecting core.async
channels to rabbitmq. `kehaar.core` is a low-level
interface. `kehaar.wire-up` is a level above. And finally,
`kehaar.configured` is above that.

In most cases, the `kehaar.configured` namespace,
`kehaar.wire-up/async->fn`, and
`kehaar.wire-up/async->fire-and-forget-fn` should be all you need to
set up services and events.

### kehaar.configured

See the example project for working examples. See the function
docstrings for all available options.

#### Convenience

You can use `kehaar.configured/init!` to set up all the below examples
in one go by passing a map with the keys `:event-exchanges`,
`:incoming-services`, `:external-services`, `:incoming-events`,
`:outgoing-events`, `:incoming-jobs`, and `:outgoing-jobs` each with a
sequence of the maps for each kind of thing. The return value of which
is a collection of all the shutdownable resources created in the
process, and it may be passed to `kehaar.configured/shutdown!` to
clean them all up.

#### Examples

> All examples are exercised by the example project. See
> example/README.md for details.

The following assumes that a rabbitmq connection is available as
`connection`.

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
(def process (wire-up/async->fire-and-forget-fn process-channel))
```

The returned function takes a single argument, which must be some
edenizable value (including `nil`) which will be passed to the
external service via the configured queue. The returned function's
return value is unspecified and should not be used.

Some notes:

* For "RPC" semantics, set `:response` to `true` in the options map
  and use `wire-up/async->fn` instead, whcih will return a core.async
  channel that will eventually contain the result.
* If the service provides a stream of responses for each request, set
  `:response` to `:streaming` and use `wire-up/async->fn`.

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
  return a sequence, which probably should be lazy, or a core.async
  channel, which must be closed when there are no more values. Each
  value in the sequence or on the channel will be returned to the
  client in order.
* Incoming messages are nacked if the thread is taking too long to
  process the messages. This allows different instances of the service
  to process those messages.
* If `:response` is `nil`, kehaar will not log error messages when the
  incoming messages lack a reply-to queue. This can reduce log noise
  if `:response` is set to `nil` on the request side.

##### You want to request work be done by a "job" service.

```clojure
(kehaar.configured/init-outgoing-job!
 connection
 {:jobs-chan 'fully.qualified/job-request-channel
  :queue "service.works.service.perform-job"})
```

Then you can create a function that "calls" that job service, like so:

```clojure
(def kick-off-job (jobs/async->job job-request-channel))
```

The returned function takes two arguments, the message to send to the
job service, which must be some ednizable value, and a function to
handle results from the service(s) producing them for the job. The
returned function's return value is the routing key created for the
job, but you are unlikely to need it.

##### You want to create a "job" service.

```clojure
(kehaar.configured/init-incoming-job!
 connection
 {:queue "service.works.service.perform-job"
  :f 'fully.qualified/handler-function})
```

Some notes:

* The handler function must be a function of three arguments: a core
  async channel to put results on, the routing key for the job, and
  the message from the process that requested the job. The message has
  been serialized and deserialized as EDN. So expect any kind of
  serializable value. The async channel must be closed when finished.

##### You want to subcontract work from one job service to another

```clojure
(kehaar.configured/init-outgoing-job!
 connection
 {:jobs-chan 'fully.qualified/subcontract-channel
  :queue "service.works.service.subcontract-part-of-job"})
```

Then you create a function that calls that job service as a
subcontractor, like so:

```clojure
(def subcontract-job (jobs/async->subjob subcontract-channel))
```

The returned function takes two arguments: the routing key for the
current job, and the message to send.

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
