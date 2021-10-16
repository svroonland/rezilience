package nl.vroste.rezilience.examples

import nl.vroste.rezilience.CircuitBreaker._
import nl.vroste.rezilience._
import zio._
import zio.clock.Clock
import zio.console.putStrLn
import zio.duration._
import zio.stream.ZStream

object CircuitBreakerExample {
  // We use Throwable as error type in this example
  def callExternalSystem(someInput: String): ZIO[Any, Throwable, Int] = ZIO.succeed(someInput.length)

  val circuitBreaker: ZManaged[Clock, Nothing, CircuitBreaker[Any]] = CircuitBreaker
    .make(
      trippingStrategy = TrippingStrategy.failureCount(maxFailures = 10),
      resetPolicy = Schedule.exponential(1.second)
    )
    .tap(cb =>
      cb.stateChanges.flatMap(
        ZStream
          .fromQueue(_)
          .tap(stateChange => UIO(println(s"State changed from ${stateChange.from} to ${stateChange.to}")))
          .runDrain
          .forkManaged
      )
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
