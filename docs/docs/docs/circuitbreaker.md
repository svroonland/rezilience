---
layout: docs
title: Circuit Breaker
permalink: docs/circuitbreaker/
---

# Circuit Breaker
Make calls to an external system through the CircuitBreaker to safeguard that system against overload. When too many calls have failed, the circuit breaker will trip and calls will fail immediately, giving the external system some time to recover. This also prevents a queue of calls waiting for response from the external system until timeout.

## Features
* Define which errors are to be considered a failure for the CircuitBreaker to count using a partial function
* Two tripping strategies:
  * Simple: trip the circuit breaker when the _number_ of consecutive failing calls exceeds some threshold.
  * Advanced: trip when the _proportion_ of failing calls exceeds some threshold.
* Exponential backoff for resetting the circuit breaker, or whatever ZIO `Schedule` fits your needs.
* Support for custom tripping strategies implementing the `TrippingStrategy` trait
* Observe state changes via a callback method

## Usage example

```scala mdoc:silent
import nl.vroste.rezilience.CircuitBreaker._
import nl.vroste.rezilience._
import zio._
import zio.clock.Clock
import zio.console.putStrLn
import zio.duration._

object CircuitBreakerExample {
  // We use Throwable as error type in this example
  def callExternalSystem(someInput: String): ZIO[Any, Throwable, Int] = ZIO.succeed(someInput.length)

  val circuitBreaker: ZManaged[Clock, Nothing, CircuitBreaker[Any]] = CircuitBreaker.make(
    trippingStrategy = TrippingStrategy.failureCount(maxFailures = 10),
    resetPolicy = Schedule.exponential(1.second),
    onStateChange = (s: State) => ZIO(println(s"State changed to ${s}")).ignore
  )

  circuitBreaker.use { cb =>
    val result: ZIO[Any, CircuitBreakerCallError[Throwable], Int] = cb(callExternalSystem("some input"))

    result
      .flatMap(r => putStrLn(s"External system returned $r"))
      .catchSome {
        case CircuitBreakerOpen =>
          putStrLn("Circuit breaker blocked the call to our external system")
        case WrappedError(e)    =>
          putStrLn(s"External system threw an exception: $e")
      }
  }
}
```
