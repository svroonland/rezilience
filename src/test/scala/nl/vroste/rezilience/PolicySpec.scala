package nl.vroste.rezilience
import nl.vroste.rezilience.Policy.WrappedError
import zio.duration.durationInt
import zio.test.Assertion._
import zio.test.environment.TestClock
import zio.test.{ DefaultRunnableSpec, _ }
import zio.{ Fiber, Promise, ZIO, ZManaged }

object PolicySpec extends DefaultRunnableSpec {
  sealed trait Error
  case object MyCallError     extends Error
  case object MyNotFatalError extends Error

  override def spec = suite("Policy")(
    testM("succeeds the first call immediately regardless of the policies") {
      val policy =
        ZManaged.mapN(RateLimiter.make(1), Bulkhead.make(100), CircuitBreaker.withMaxFailures(10))(
          Policy.common(_, _, _)
        )

      policy.use { policy =>
        for {
          result <- policy(ZIO.succeed(123))
        } yield assert(result)(equalTo(123))

      }
    },
    testM("fails the first call when retry is disabled") {
      val policy =
        ZManaged.mapN(RateLimiter.make(1), Bulkhead.make(100), CircuitBreaker.withMaxFailures(10))(
          Policy.common(_, _, _)
        )

      policy.use { policy =>
        for {
          result <- policy(ZIO.fail(MyCallError)).flip
        } yield assert(result)(equalTo(WrappedError(MyCallError)))
      }
    },
    testM("fail with a circuit breaker error after too many failed calls") {
      val policy =
        ZManaged.mapN(
          RateLimiter.make(2),
          Bulkhead.make(100),
          CircuitBreaker.withMaxFailures(1)
        )(Policy.common(_, _, _))

      policy.use { policy =>
        for {
          _      <- policy(ZIO.fail(MyCallError)).flip
          result <- policy(ZIO.fail(MyCallError)).flip
        } yield assert(result)(equalTo(Policy.CircuitBreakerOpen))
      }
    },
    testM("fail with a bulkhead error after too many calls in progress") {
      val policy =
        ZManaged.mapN(
          RateLimiter.make(10),
          Bulkhead.make(1, maxQueueing = 1),
          CircuitBreaker.withMaxFailures(1)
        )(Policy.common(_, _, _))

      policy.use { policy =>
        for {
          latch  <- Promise.make[Nothing, Unit]
          latch3 <- Promise.make[Nothing, Unit]
          _      <- policy(latch.succeed(()) *> latch3.await).fork // This one will go in-flight immediately
          _      <- latch.await
          result <-
            policy(ZIO.unit).flip raceFirst policy(ZIO.unit).flip // One of these is enqueued, one is rejected
        } yield assert(result)(equalTo(Policy.BulkheadRejection))
      }
    },
    testM("rate limit") {
      val policy =
        ZManaged.mapN(
          RateLimiter.make(2),
          Bulkhead.make(10),
          CircuitBreaker.withMaxFailures[Error](1)
        )(Policy.common(_, _, _))

      policy.use { policy =>
        for {
          _             <- policy(ZIO.unit)
          _             <- policy(ZIO.unit)
          fib           <- policy(ZIO.succeed(123)).fork
          _             <- TestClock.adjust(0.seconds)
          initialStatus <- fib.status
          _             <- TestClock.adjust(1.seconds)
          _             <- fib.join
        } yield assert(initialStatus)(Assertion.isSubtype[Fiber.Status.Suspended](anything))
      }
    }
  )
}
