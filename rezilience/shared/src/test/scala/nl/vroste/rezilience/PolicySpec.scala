package nl.vroste.rezilience
import nl.vroste.rezilience.Policy.WrappedError
import zio.test.Assertion._
import zio.test.TestAspect.{ nonFlaky, timeout }
import zio.test._
import zio.{ durationInt, Fiber, Promise, ZIO }

object PolicySpec extends DefaultRunnableSpec {
  sealed trait Error
  case object MyCallError     extends Error
  case object MyNotFatalError extends Error

  override def spec = suite("Policy")(
    test("succeeds the first call immediately regardless of the policies") {
      val policy =
        (RateLimiter.make(1) zip
          Bulkhead.make(100) zip
          CircuitBreaker.withMaxFailures(10)).map { case (rl, bh, cb) => Policy.common(rl, bh, cb) }

      ZIO.scoped {
        for {
          policy <- policy
          result <- policy(ZIO.succeed(123))
        } yield assert(result)(equalTo(123))

      }
    },
    test("fails the first call when retry is disabled") {
      val policy =
        (RateLimiter.make(1) zip
          Bulkhead.make(100) zip
          CircuitBreaker.withMaxFailures(10)).map { case (rl, bh, cb) => Policy.common(rl, bh, cb) }

      ZIO.scoped {
        for {
          policy <- policy
          result <- policy(ZIO.fail(MyCallError)).flip
        } yield assert(result)(equalTo(WrappedError(MyCallError)))
      }
    },
    test("fail with a circuit breaker error after too many failed calls") {
      val policy =
        (RateLimiter.make(2) zip
          Bulkhead.make(100) zip
          CircuitBreaker.withMaxFailures(1)).map { case (rl, bh, cb) => Policy.common(rl, bh, cb) }

      ZIO.scoped {
        for {
          policy <- policy
          _      <- policy(ZIO.fail(MyCallError)).flip
          result <- policy(ZIO.fail(MyCallError)).flip
        } yield assert(result)(equalTo(Policy.CircuitBreakerOpen))
      }
    },
    test("fail with a bulkhead error after too many calls in progress") {
      val policy =
        (RateLimiter.make(10) zip
          Bulkhead.make(1, maxQueueing = 1) zip
          CircuitBreaker.withMaxFailures(1)).map { case (rl, bh, cb) => Policy.common(rl, bh, cb) }

      ZIO.scoped {
        for {
          policy <- policy
          latch  <- Promise.make[Nothing, Unit]
          latch3 <- Promise.make[Nothing, Unit]
          _      <- policy(latch.succeed(()) *> latch3.await).fork // This one will go in-flight immediately
          _      <- latch.await
          result <-
            policy(ZIO.unit).flip raceFirst policy(ZIO.unit).flip // One of these is enqueued, one is rejected
        } yield assert(result)(equalTo(Policy.BulkheadRejection))
      }
    },
    test("rate limit") {
      val policy =
        (RateLimiter.make(2) zip
          Bulkhead.make(10) zip
          CircuitBreaker.withMaxFailures(1)).map { case (rl, bh, cb) => Policy.common(rl, bh, cb) }

      ZIO.scoped {
        for {
          policy        <- policy
          _             <- policy(ZIO.unit)
          _             <- policy(ZIO.unit)
          fib           <- policy(ZIO.succeed(123)).fork
          _             <- TestClock.adjust(0.seconds)
          initialStatus <- fib.status
          _             <- TestClock.adjust(1.seconds)
          _             <- fib.join
        } yield assert(initialStatus)(isSubtype[Fiber.Status.Suspended](anything))
      }
    }
  ) @@ nonFlaky @@ timeout(60.seconds)
}
