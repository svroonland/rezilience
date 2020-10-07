---
layout: docs
title: Circuit Breaker
permalink: docs/circuitbreaker/
---

# Circuit Breaker
Circuit Breaker is a reactive resilience strategy to safeguard an external system against overload. It will also prevent queueing up of calls to an already struggling system.

## Behavior
A Circuit Breaker starts in the 'closed' state. All calls are passed through in this state. Any failures are counted. When too many failures have occurred, the breaker goes to the 'open' state. Calls made in this state will fail immediately with a `CircuitBreakerOpen` error. 

After some time, the circuit breaker will reset to the 'half open' state. In this state, one call can pass through. If this call succeeds, the circuit breaker goes back to the 'closed' state. If it fails, the breaker goes again to the 'open' state.

CircuitBreaker uses a ZIO `Schedule` to determine the reset interval. By default, this is an exponential backoff schedule, so that reset intervals double with each iteration, capped at some maximum value. You can however provide any `Schedule` that fits your needs.

## Failure counting modes
CircuitBreaker has two modes for counting failures:

* Failure Count  
  Trip the circuit breaker when the _number_ of consecutive failing calls exceeds some threshold. This is implemented in `TrippingStrategy.failureCount`
* Failure Rate  
  Trip when the _proportion_ of failing calls exceeds some threshold. The threshold and the sample period can be specified. You can specify a minimum call count to avoid tripping at very low call rates. This mode is implemented in `TrippingStrategy.failureRate`
  
Custom tripping strategies can be implemented by extending `TrippingStrategy`.

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
    resetPolicy = Retry.Schedules.exponentialBackoff(min = 1.second, max = 1.minute)
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

## Responding to a subset of errors
Often you will want the Circuit Breaker to respond only to certain types of errors from your external system call, while passing through other errors that indicate normal operation. Use the `isFailure` parameter of `CircuitBreaker.make` to define which errors are regarded by the Circuit Breaker.

```scala mdoc:silent
sealed trait Error
case object ServiceError     extends Error
case object UserError extends Error

val isFailure: PartialFunction[Error, Boolean] = {
  case UserError => false
  case _: Error        => true
}

def callWithServiceError: ZIO[Any, Error, Unit] = ZIO.fail(ServiceError)
def callWithUserError: ZIO[Any, Error, Unit] = ZIO.fail(UserError)

CircuitBreaker.make(
  trippingStrategy = TrippingStrategy.failureCount(maxFailures = 10),
  isFailure = isFailure
).use { circuitBreaker =>
  for {
    _ <- circuitBreaker(callWithUserError) // Will not be counted as failure by the circuit breaker
    _ <- circuitBreaker(callWithServiceError) // Will be counted as failure
  } yield ()
}
```

## Monitoring
You may want to monitor circuit breaker failures and trigger alerts when the circuit breaker trips. For this purpose, CircuitBreaker publishes state changes via a callback provided to `make`. Usage:

```scala mdoc:silent
CircuitBreaker.make(
  trippingStrategy = TrippingStrategy.failureCount(maxFailures = 10),
  onStateChange = (s: State) => ZIO(println(s"State changed to ${s}")).ignore
).use { circuitBreaker =>
  // Make calls to an external system
  circuitBreaker(ZIO.unit) // etc
}
```
