# kehaar-example

An example project that demonstrates the use of Kehaar.

The project provides a service (calculating Fibonacci numbers) over
RabbitMQ, calls that service over RabbitMQ, sends messages to a topic
when it calcultes Fibonacci numbers, and listens on that topic and
reacts to it.

## Usage

This project assumes a RabbitMQ server running locally on its standard
port.

### Standard response/request and event functionality

Execute `lein run`.

It will make 500 requests to the Fibonacci service, logging requests,
responses and events received.

See core.clj for details.

### Streaming functionality

Run `lein streaming-producer` in one terminal and `lein
streaming-consumer` in another. The consumer will make requests to the
producer which will stream them back.

See streaming/producer.clj and streaming/consumer.clj.

### Jobs

Run `lein job-threes` and `lein job-counter` in two terminals and then
`lein job-instigator` in a third terminal. The instigator will make a
job request to the counter, which will start sending results, but will
also subcontract some of the work to the "threes" worker.

See jobs/threes.clj, jobs/counter.clj, and jobs/instigator.clj.
