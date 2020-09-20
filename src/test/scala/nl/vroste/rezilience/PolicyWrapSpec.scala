package nl.vroste.rezilience
import nl.vroste.rezilience.PolicyWrap.WrappedError
import zio.duration.durationInt
import zio.{ Fiber, Promise, Schedule, ZIO, ZManaged }
import zio.test.DefaultRunnableSpec
import zio.test._
import zio.test.Assertion._
import zio.test.environment.TestClock

object PolicyWrapSpec extends DefaultRunnableSpec {
  sealed trait Error
  case object MyCallError     extends Error
  case object MyNotFatalError extends Error

  override def spec = suite("PolicyWrap")(
    testM("succeeds the first call immediately regardless of the policies") {
      val policy =
        ZManaged.mapN(RateLimiter.make(1), Bulkhead.make(100), CircuitBreaker.withMaxFailures[Error](10))(
          PolicyWrap.make(_, _, _)
        )

      policy.use { policy =>
        for {
          result <- policy.call(ZIO.succeed(123))
        } yield assert(result)(equalTo(123))

      }
    },
    testM("fails the first call when retry is disabled") {
      val policy =
        ZManaged.mapN(RateLimiter.make(1), Bulkhead.make(100), CircuitBreaker.withMaxFailures[Error](10))(
          PolicyWrap.make(_, _, _)
        )

      policy.use { policy =>
        for {
          result <- policy.call(ZIO.fail(MyCallError)).flip
        } yield assert(result)(equalTo(WrappedError(MyCallError)))
      }
    },
    testM("fail with a circuit breaker error after too many failed calls") {
      val policy =
        ZManaged.mapN(
          RateLimiter.make(2),
          Bulkhead.make(100),
          CircuitBreaker.withMaxFailures[Error](1)
        )(PolicyWrap.make(_, _, _))

      policy.use { policy =>
        for {
          _      <- policy.call(ZIO.fail(MyCallError)).flip
          result <- policy.call(ZIO.fail(MyCallError)).flip
        } yield assert(result)(equalTo(PolicyWrap.CircuitBreakerOpen))
      }
    },
    testM("fail with a bulkhead error after too many calls in progress") {
      val policy =
        ZManaged.mapN(
          RateLimiter.make(10),
          Bulkhead.make(1, maxQueueing = 1),
          CircuitBreaker.withMaxFailures[Error](1)
        )(PolicyWrap.make(_, _, _))

      policy.use { policy =>
        for {
          latch  <- Promise.make[Nothing, Unit]
          latch2 <- Promise.make[Nothing, Unit]
          _      <- policy.call(latch.succeed(()) *> latch2.await).fork
          _      <- policy.call(latch.succeed(()) *> latch2.await).fork
          _      <- latch.await
          result <- policy.call(ZIO.succeed(123)).flip
          _      <- latch2.succeed(())
        } yield assert(result)(equalTo(PolicyWrap.BulkheadRejection))
      }
    },
    testM("rate limit") {
      val policy =
        ZManaged.mapN(
          RateLimiter.make(2),
          Bulkhead.make(10),
          CircuitBreaker.withMaxFailures[Error](1)
        )(PolicyWrap.make(_, _, _))

      policy.use { policy =>
        for {
          _             <- policy.call(ZIO.unit)
          _             <- policy.call(ZIO.unit)
          fib           <- policy.call(ZIO.succeed(123)).fork
          _             <- TestClock.adjust(0.seconds)
          initialStatus <- fib.status
          _             <- TestClock.adjust(1.seconds)
          _             <- fib.join
        } yield assert(initialStatus)(Assertion.isSubtype[Fiber.Status.Suspended](anything))
      }
    }
  )

}
