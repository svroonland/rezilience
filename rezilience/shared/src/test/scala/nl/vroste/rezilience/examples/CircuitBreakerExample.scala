package nl.vroste.rezilience.examples

import nl.vroste.rezilience.CircuitBreaker._
import nl.vroste.rezilience._
import zio._

object CircuitBreakerExample extends zio.ZIOAppDefault {
  // We use Throwable as error type in this example
  def callExternalSystem(someInput: String): ZIO[Any, Throwable, Int] = ZIO.succeed(someInput.length)

  val circuitBreaker: ZIO[Scope, Nothing, CircuitBreaker[Any]] = CircuitBreaker.make(
    trippingStrategy = TrippingStrategy.failureCount(maxFailures = 10),
    resetPolicy = Schedule.exponential(1.second)
  )

  override def run =
    circuitBreaker.flatMap { cb =>
      val result: ZIO[Any, CircuitBreakerCallError[Throwable], Int] = cb(callExternalSystem("some input"))

      result
        .flatMap(r => Console.printLine(s"External system returned $r"))
        .catchSome {
          case CircuitBreakerOpen =>
            Console.printLine("Circuit breaker blocked the call to our external system")
          case WrappedError(e)    =>
            Console.printLine(s"External system threw an exception: $e")
        }
    }
}
