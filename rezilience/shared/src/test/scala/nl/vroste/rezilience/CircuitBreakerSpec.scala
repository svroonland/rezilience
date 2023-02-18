package nl.vroste.rezilience

import nl.vroste.rezilience.CircuitBreaker.State
import zio.duration._
import zio.{ Queue, Schedule, ZIO }
import zio.test.Assertion._
import zio.test.TestAspect.nonFlaky
import zio.test._
import zio.test.environment.TestClock

object CircuitBreakerSpec extends DefaultRunnableSpec {
  sealed trait Error
  case object MyCallError     extends Error
  case object MyNotFatalError extends Error

  // TODO add generator based checks with different nr of parallel calls to check
  // for all kinds of race conditions
  def spec = suite("CircuitBreaker")(
    testM("lets successful calls through") {
      CircuitBreaker.withMaxFailures(10, Schedule.exponential(1.second)).use { cb =>
        for {
          _ <- cb(ZIO.unit).repeat(Schedule.recurs(20))
        } yield assertCompletes
      }
    },
    testM("fails fast after max nr failures calls") {
      CircuitBreaker
        .withMaxFailures(100, Schedule.exponential(1.second))
        .use { cb =>
          for {
            _      <-
              ZIO.foreachPar_(1 to 105)(_ => cb(ZIO.fail(MyCallError)).either.tapCause(c => ZIO.debug(c)))
            result <- cb(ZIO.fail(MyCallError)).either
          } yield assert(result)(isLeft(equalTo(CircuitBreaker.CircuitBreakerOpen)))
        }
    } @@ TestAspect.diagnose(20.seconds),
    testM("ignore failures that should not be considered a failure") {
      val isFailure: PartialFunction[Error, Boolean] = {
        case MyNotFatalError => false
        case _: Error        => true
      }

      CircuitBreaker
        .withMaxFailures(3, Schedule.exponential(1.second), isFailure)
        .use { cb =>
          for {
            _      <- cb(ZIO.fail(MyNotFatalError)).either.repeatN(3)
            result <- cb(ZIO.fail(MyCallError)).either
          } yield assertTrue(
            result.left.toOption.get != CircuitBreaker.CircuitBreakerOpen
          )
        }
    },
    testM("reset to closed state after reset timeout") {
      (for {
        stateChanges <- Queue.unbounded[State].toManaged_
        cb           <- CircuitBreaker.withMaxFailures(
                          10,
                          Schedule.exponential(1.second),
                          onStateChange = stateChanges.offer(_).ignore
                        )
      } yield (stateChanges, cb)).use { case (stateChanges, cb) =>
        for {
          _ <- ZIO.foreach_(1 to 10)(_ => cb(ZIO.fail(MyCallError)).either)
          _ <- stateChanges.take
          _ <- TestClock.adjust(3.second)
          _ <- stateChanges.take
          _ <- cb(ZIO.unit)
        } yield assertCompletes
      }
    },
    testM("retry exponentially") {
      (for {
        stateChanges <- Queue.unbounded[State].toManaged_
        cb           <- CircuitBreaker.withMaxFailures(
                          3,
                          Schedule.exponential(base = 1.second, factor = 2.0),
                          onStateChange = stateChanges.offer(_).ignore
                        )
      } yield (stateChanges, cb)).use { case (stateChanges, cb) =>
        for {
          _  <- ZIO.foreach_(1 to 3)(_ => cb(ZIO.fail(MyCallError)).either)
          s1 <- stateChanges.take // Open
          _  <- TestClock.adjust(1.second)
          s2 <- stateChanges.take // HalfOpen
          _  <- cb(ZIO.fail(MyCallError)).either
          s3 <- stateChanges.take // Open again
          s4 <- stateChanges.take.timeout(1.second) <& TestClock.adjust(1.second)
          _  <- TestClock.adjust(1.second)
          s5 <- stateChanges.take
          _  <- cb(ZIO.unit)
          s6 <- stateChanges.take
        } yield assert(s1)(equalTo(State.Open)) &&
          assert(s2)(equalTo(State.HalfOpen)) &&
          assert(s3)(equalTo(State.Open)) &&
          assert(s4)(isNone) &&
          assert(s5)(equalTo(State.HalfOpen)) &&
          assert(s6)(equalTo(State.Closed))
      }
    },
    testM("reset the exponential timeout after a Closed-Open-HalfOpen-Closed") {
      (for {
        stateChanges <- Queue.unbounded[State].toManaged_
        cb           <- CircuitBreaker.withMaxFailures(
                          3,
                          Schedule.exponential(base = 1.second, factor = 2.0),
                          onStateChange = stateChanges.offer(_).ignore
                        )
      } yield (stateChanges, cb)).use { case (stateChanges, cb) =>
        for {
          _ <- ZIO.foreach_(1 to 3)(_ => cb(ZIO.fail(MyCallError)).either)
          _ <- stateChanges.take // Open
          _ <- TestClock.adjust(1.second)
          _ <- stateChanges.take // HalfOpen

          _ <- cb(ZIO.fail(MyCallError)).either
          _ <- stateChanges.take // Open again, this time with double reset timeout

          _ <- TestClock.adjust(2.second)
          _ <- stateChanges.take // HalfOpen

          _ <- cb(ZIO.unit)
          _ <- stateChanges.take // Closed again

          _ <- ZIO.foreach_(1 to 3)(_ => cb(ZIO.fail(MyCallError)).either)
          _ <- stateChanges.take // Open

          // Reset time should have re-initialized again
          _  <- TestClock.adjust(1.second)
          s1 <- stateChanges.take // HalfOpen
        } yield assert(s1)(equalTo(State.HalfOpen))
      }
    }
  ) @@ nonFlaky
}
