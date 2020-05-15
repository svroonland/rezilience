package nl.vroste.rezilience

import nl.vroste.rezilience.CircuitBreaker.State
import zio.console.Console
import zio.duration._
import zio.{ Queue, Schedule, ZIO }
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestClock
import zio.console.putStrLn

object CircuitBreakerSpec extends DefaultRunnableSpec {
  sealed trait Error
  case object CircuitBreakerClosedError extends Error
  case object MyCallError               extends Error

  def spec = suite("CircuitBreaker")(
    testM("lets successful calls through") {
      CircuitBreaker.make[Error](10, 1.second, CircuitBreakerClosedError).use { cb =>
        for {
          _ <- cb.call(ZIO.unit).repeat(Schedule.recurs(20))
        } yield assertCompletes
      }
    },
    testM("fails fast after max nr failures calls") {
      CircuitBreaker.make[Error](10, 1.second, CircuitBreakerClosedError).use { cb =>
        for {
          _      <- ZIO.foreach(1 to 10)(_ => cb.call(ZIO.fail(MyCallError)).either)
          result <- cb.call(ZIO.fail(MyCallError)).either
        } yield assert(result)(isLeft(equalTo(CircuitBreakerClosedError)))
      }
    },
    testM("reset to closed state after reset timeout") {
      (for {
        stateChanges <- Queue.unbounded[State].toManaged_
        console      <- ZIO.environment[Console].toManaged_
        cb <- CircuitBreaker.make[Error](
               10,
               1.second,
               CircuitBreakerClosedError,
               s => putStrLn(s"State changes to: ${s.toString}").provide(console) *> stateChanges.offer(s).ignore
             )
      } yield (stateChanges, cb)).use {
        case (stateChanges, cb) =>
          for {
            _ <- ZIO.foreach(1 to 10)(_ => cb.call(ZIO.fail(MyCallError)).either)
            _ <- stateChanges.take
            _ <- TestClock.adjust(3.second)
            _ <- stateChanges.take
            _ <- cb.call(ZIO.unit)
          } yield assertCompletes
      }
    }
  )
}
