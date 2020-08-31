[![Bintray](https://img.shields.io/bintray/v/vroste/maven/rezilience?label=latest)](https://bintray.com/vroste/maven/rezilience/_latestVersion)

# Rezilience

## About

`rezilience` is a ZIO-native collection of utilities for making asynchronous systems more resilient to failures.

It is inspired by [Polly](https://github.com/App-vNext/Polly) and [Akka](https://doc.akka.io/docs/akka/current/common/circuitbreaker.html)

It currently consists of:

* `CircuitBreaker`
* `Bulkhead`: limiting the usage of a resource

and will include in future releases:

* `Retry`: utilities for retrying on failures

## Installation

Add to your build.sbt:

```scala
resolvers += Resolver.jcenterRepo
libraryDependencies += "nl.vroste" %% "rezilience" % "<version>"
```

The latest version is built against ZIO 1.0.0.

## Circuit Breaker
Make calls to an (external) resource through the CircuitBreaker to safeguard the resource against overload. When too many calls have failed, the circuit breaker will trip and calls will fail immediately. This also prevents a queue of calls waiting for response from the resource until timeout.

### Features
* Support for custom error type (the `E` in `ZIO[R, E, A]`) for indicating failures. As with ZIO, you are not limited by `Exception`s for failures. 
* Define which errors are to be considered a failure for the CircuitBreaker to count using a partial function
* Two tripping strategies:
  * Simple: trip the circuit breaker when the _number_ of consecutive failing calls exceeds some threshold.
  * Advanced: trip when the _proportion_ of failing calls exceeds some threshold.
* Exponential backoff for resetting the circuit breaker, or whatever ZIO `Schedule` fits your needs.
* Support for custom tripping strategies via the `TrippingStrategy` trait
* Observe state changes via callback method

### Usage

A `CircuitBreaker` is a Managed resource.

```scala
import zio._
import nl.vroste.rezilience._

// We use Throwable as error type in this example 
def myCallToExternalResource(someInput: String): ZIO[Any, Throwable, Int] = ...

val circuitBreaker: ZManaged[Clock, Nothing, CircuitBreaker] = CircuitBreaker.make(
    strategy = TrippingStrategy.failureCount(maxFailures), resetPolicy, onStateChange),
    resetPolicy = Schedule.exponential(1.second),
    onStateChange = (s: State) => ZIO(println(s"State changed to ${s}")).ignore
    )

circuitBreaker.use { cb =>
    val result: ZIO[Any, CircuitBreakerCallError[Throwable]] =
        cb.withCircuitBreaker(myCallToExternalResource("some input"))
}
```

## Bulkhead

`Bulkhead` limits the resources used by some system by limiting the number of concurrent calls to that system. Calls that exceed that number are rejected with a `BulkheadError`. To ensure good utilisation of the system, however, there is a queue/buffer of waiting calls.
 
It also prevents queueing up of requests, which consume resources in the calling system, by rejecting calls when the queue is full.