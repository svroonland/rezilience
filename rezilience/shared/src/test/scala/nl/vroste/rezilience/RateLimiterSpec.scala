package nl.vroste.rezilience
import zio.{ clock, Promise, ZIO }
import zio.duration._
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect.{ nonFlaky, timeout }
import zio.test.environment.TestClock

object RateLimiterSpec extends DefaultRunnableSpec {
  override def spec = suite("RateLimiter")(
    testM("execute up to max calls immediately") {
      RateLimiter.make(10, 1.second).use { rl =>
        for {
          now   <- clock.instant
          times <- ZIO.foreach((1 to 10).toList)(_ => rl(clock.instant))
        } yield assert(times)(forall(equalTo(now)))
      }
    },
    testM("succeed with the result of the call") {
      RateLimiter.make(10, 1.second).use { rl =>
        for {
          result <- rl(ZIO.succeed(3))
        } yield assert(result)(equalTo(3))
      }
    },
    testM("fail with the result of a failed call") {
      RateLimiter.make(10, 1.second).use { rl =>
        for {
          result <- rl(ZIO.fail(None)).either
        } yield assert(result)(isLeft(isNone))
      }
    },
    testM("continue after a failed call") {
      RateLimiter.make(10, 1.second).use { rl =>
        for {
          _ <- rl(ZIO.fail(None)).either
          _ <- rl(ZIO.succeed(3))
        } yield assertCompletes
      }
    },
    testM("holds back up calls after the max") {
      RateLimiter.make(10, 1.second).use { rl =>
        for {
          now   <- clock.instant
          fib   <- ZIO.foreach((1 to 20).toList)(_ => rl(clock.instant)).fork
          _     <- TestClock.adjust(1.second)
          later <- clock.instant
          times <- fib.join
        } yield assert(times.take(10))(forall(equalTo(now))) && assert(times.drop(10))(
          forall(isGreaterThan(now) && isLessThanEqualTo(later))
        )
      }
    },
    testM("will interrupt the effect when a call is interrupted") {
      RateLimiter.make(10, 1.second).use { rl =>
        for {
          latch       <- Promise.make[Nothing, Unit]
          interrupted <- Promise.make[Nothing, Unit]
          fib         <- rl((latch.succeed(()) *> ZIO.never).onInterrupt(interrupted.succeed(()))).fork
          _           <- latch.await
          _           <- fib.interrupt
          _           <- interrupted.await
        } yield assertCompletes
      }

    }
  ) @@ nonFlaky @@ timeout(60.seconds)
}
