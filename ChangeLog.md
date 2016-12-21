# Change Log

## Changes between Kehaar 0.8.0 and HEAD

Added thread count option to `init-incoming-event!`.

## Changes between Kehaar 0.7.2 and 0.8.0

### kehaar.configured

A new namespace with functions that allow for a more declarative
description of your system.

## Changes between Kehaar 0.7.1 and 0.7.2

Fixed the 3-arity version of `external-service-fire-and-forget` to
call the 5-arity version of itself.

## Changes between Kehaar 0.7.0 and 0.7.1

### Fire and forget

Added `external-service-fire-and-forget` and
`async->fire-and-forget-fn`, used much like their counterparts without
"fire-and-forget" in their names to send messages to an external
service but without waiting for a response.

Added an optional argument to `incoming-service` called
`ignore-no-reply-to`, which causes it to no longer log warnings when a
message comes without the `:reply-to` metadata set. Good for
decreasing the noise in a service you expect to be used by the "fire
and forget" functions above.

## Changes between Kehaar 0.6.0 and 0.7.0

### Configurable number of threads to handle messages with

`start-responder!`, `start-streaming-responder!`, and
`start-event-handler!` can now take an extra argument: a number of
threads to use to pull messages from their input channels. The threads
will be created and be waiting on new messages immediately. The
default number of threads is 10.

### Sleeping on nack

When `rabbit=>async` nacks a message, it sleeps for one second before
attempting to take another message. Eventually this will be
configurable.

Together with the limited number of threads for message handlers, this
better allows for backpressure from core.async to RabbitMQ.

## Changes between Kehaar 0.5.0 and 0.6.0

### Streaming responders

Added `start-streaming-responder!` and `streaming-external-service` to
the `kehaar.wire-up` namespace for starting and consuming streaming
responses. Those functions are used for streaming responders in place
of `start-responder!` and `external-service` respectively.

A streaming responder function merely needs to return a sequence
(lazy, if you like) and the values from that sequence will be sent
across RabbitMQ to a core.async channel on the consumer's side.

## Changes between Kehaar 0.4.0 and 0.5.0

### kehaar.rabbitmq/connect-with-retries

Added `kehaar.rabbitmq/connect-with-retries` function to make connecting to
RabbitMQ brokers more robust.

### Backpressure

**This is a major change**. It renames and reworks the existing
functionality.

The main motivation for this was to make kehaar more robust when faced
with errors. EDN parse errors would cause the entire handler to
crash. And kehaar would read as many messages as it could at a time,
potentially overloading the server with no recourse for backpressure.

This rewrite solves those two problems while renaming and reworking
the abstractions. The main difference is that the functions which pump
messages to and from rabbit <=> core.async now pass the payload and
metadata. This simplified the code a lot since we often need the
metadata.

Event handlers and service responders now run in their own
threads. call start-event-handler! and start-responder! in server
initialization to start those. The return value of event handlers is
ignored, but responders can return any value or a channel which will
contain the value. This lets things remain asynchronous.

**This is a breaking change.** Many "lower-level" function in `core`
are very different. Other functions have been moved, renamed, and
their arguments are different.

### Example project

An example project has been added at /example, to demonstrate how to
use the various functions in the `wire-up` namespace.

## Changes between Kehaar 0.3.0 and 0.4.0

### kehaar.core/ch->response-fn

In 0.2.0 `ch->response-fn` started returning promises instead of core.async
channels. That is now reverted back to core.async channels.
**This is a breaking change.**

### kehaar.wire-up

The `kehaar.wire-up` namespace contains a higher-level interface for
declaring and setting up queues.

### Updated Clojure dependency to 1.7.0.

It came out.

## Changes between Kehaar 0.2.1 and 0.3.0

### Add `kehaar.core` namespace

Since single-level namespaces are not recommended in Clojure, we have
moved the code that was in `kehaar` to a new `kehaar.core` ns.
**This is a breaking change.** You will need to update all your
`(ns ... (:require [kehaar]))` forms in your code to look more like
this instead: `(ns ... (:require [kehaar.core]))`.

### Ack-on-take in `rabbit->async`

Previously kehaar auto-acked every incoming RabbitMQ message. Now it
acks only when something successfully consumes the message from the
core.async channel that it is forwarded to.

### Logging

There are now debug-level log messages when Kehaar consumes a RabbitMQ
message and when it forwards them on to core.async channels.

There are also warn-level log messages when it tries to take from a
closed core.async channel.

This adds a dependency on `clojure.tools.logging` 0.3.1.

### Updated dependencies

Use Clojure 1.7.0-RC2

## Changes between Kehaar 0.2.0 and 0.2.1

### `nil?` checks when taking values from async channels

Pulling from a core.async channel will return `nil` if the channel is
closed, so now we check for that and stop trying to handle those
messages and stop trying to pull more.

### Updated dependencies

Use Clojure 1.7.0-beta3 and Langohr 3.2.0.

## Changes between Kehaar 0.1.0 and 0.2.0

### kehaar/async->rabbit

`kehaar/async->rabbit` no longer declares the queue it operates
on. **This is a breaking change.** Queues must now be already
declared. This allows, for example, the queue to be
[a server-named, exclusive, auto-deleted queue](http://clojurerabbitmq.info/articles/queues.html#declaring-a-temporary-exclusive-queue).

### kehaar/rabbit->async

The old `kehaar/rabbit->async` function has been renamed
`kehaar/rabbit->async-handler-fn`, and `kehaar/rabbit->async` now
takes the RabbitMQ queue and the async channel, handling the
subscription for you. **This is a breaking change.**

`rabbit->async` and `async->rabbit` now appropriately mirror the
other.

### kehaar/rabbit->async-handler-fn

`kehaar/rabbit->async-handler-fn` now blocks if the async channel's
buffer is full, providing the opportunity for some back pressure.

### kehaar/ch->response-fn

`kehaar/ch->response-fn` now returns promises instead of async
channels for the caller to wait on. **This is a breaking change.**

### kehaar/wire-up-service

`kehaar/wire-up-service` no longer declares the queue it operates on
either. **This is a breaking change.** Queues must now already be
delcared. Additionally, internally, it uses a server-named, exclusive,
auto-deleted queue.

### kehaar/responder

`kehaar/simple-responder` has been renamed `kehaar/fn->handler-fn` and
a new function `kehaar/responder` has been made which takes a RabbitMQ
channel and queue and a function to apply to all messages, replying on
the reply-to queue with the result. **This is a breaking change.**

### Tests using RabbitMQ

There are now tests which use RabbitMQ, however they are not run by
default with `lein run`. In order to run the RabbitMQ tests, start
`rabbitmq-server` with its default configuration and run `lein test
:rabbit-mq`. To run all tests, run `lein test :all`.

Travis CI has been updated to run those tests as well.
