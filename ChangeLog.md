# Change Log

## Changes between Kehaar 0.1.0 and 0.x

### kehaar/async->rabbit

`kehaar/async->rabbit` no longer declares the queue it operates
on. **This is a breaking change.** Queues must now be already
declared. This allows, for example, the queue to be
[a server-named, exclusive, auto-deleted queue](http://clojurerabbitmq.info/articles/queues.html#declaring-a-temporary-exclusive-queue).

### Tests using RabbitMQ

There are now tests which use RabbitMQ, however they are not run by
default with `lein run`. In order to run the RabbitMQ tests, start
`rabbitmq-server` with its default configuration and run `lein test
:rabbit-mq`. To run all tests, run `lein test :all`.

Travis CI has been updated to run those tests as well.
