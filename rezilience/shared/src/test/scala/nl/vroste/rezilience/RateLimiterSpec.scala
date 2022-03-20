package nl.vroste.rezilience
import zio.{ clock, Fiber, Promise, Ref, ZIO }
import zio.duration._
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect.{ diagnose, nonFlaky, timeout }
import zio.test.environment.TestClock

import java.time.Instant

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
    testM("is not affected by stream chunk size") {
      RateLimiter.make(10, 1.second).use { rl =>
        for {
          now           <- clock.instant
          times1        <-
            ZIO.foreachPar((1 to 5).toList)(_ => rl(clock.instant))
          secondCallFib <- ZIO.foreachPar((1 to 15).toList)(_ => rl(clock.instant).fork)
          _             <- TestClock.adjust(1.second)
          times2        <- ZIO.foreach(secondCallFib)(_.join)

          times = times1 ++ times2

        } yield assert(times.filter(_ == now))(hasSize(equalTo(10)))
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
    suite("interruption")(
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
      },
      testM("will not start execution of an effect when it is interrupted before getting its turn to execute") {
        RateLimiter.make(1, 1.second).use { rl =>
          for {
            count        <- Ref.make(0)
            _            <- rl(ZIO.unit)
            fib          <- rl(count.set(1)).fork
            interruption <- fib.interrupt.fork
            _            <- interruption.join
            c            <- count.get
          } yield assert(c)(equalTo(0))
        }
      },
      testM("will wait for interruption to complete of an effect that is already executing") {
        RateLimiter.make(1, 1.second).use { rl =>
          for {
            latch             <- Promise.make[Nothing, Unit]
            effectInterrupted <- Ref.make(0)
            fib               <- rl {
                                   (latch.succeed(()) *> ZIO.never).onInterrupt(effectInterrupted.set(1))
                                 }.fork
            _                 <- latch.await
            _                 <- fib.interrupt
            interruptions     <- effectInterrupted.get
          } yield assert(interruptions)(equalTo(1))
        }
      },
      testM("will make effects wait for interrupted effects to pass through the rate limiter") {
        RateLimiter.make(1, 1.second).use { rl =>
          for {
            _  <- rl(ZIO.unit)               // Execute one
            f1 <- rl(ZIO.unit).fork
            _  <- TestClock.adjust(1.second) // This ensures that the second RL call is in the stream before we interrupt
            _  <- f1.interrupt

            // This one will have to wait 1 seconds
            fib               <- rl(clock.instant).fork
            _                 <- TestClock.adjust(1.second)
            lastExecutionTime <- fib.join
          } yield assert(lastExecutionTime)(equalTo(Instant.ofEpochSecond(2)))
        }
      },
      testM("will not include interrupted effects in the throttling") {
        val rate = 10
        RateLimiter.make(rate, 1.second).use { rl =>
          for {
            latch    <- Promise.make[Nothing, Unit]
            latched  <- Ref.make(0)
            continue <- Promise.make[Nothing, Unit]
            _        <- ZIO.replicateM(rate) {
                          rl {
                            ZIO.whenM(latched.updateAndGet(_ + 1).map(_ == rate))(latch.succeed(())) *>
                              continue.await
                          }.fork
                        }
            _        <- latch.await
            // Now we have 10 in progress. Now submit another bunch. We can interrupt these
            fibers   <- ZIO.replicateM(1000) {
                          rl {
                            ZIO.unit
                          }.fork
                        }
            _        <- Fiber.interruptAll(fibers)
            f1       <- rl(ZIO.unit).fork
            _        <- TestClock.adjust(1.second) // This ensures that new tokens become available from the bucket
            _        <- f1.join
          } yield assertCompletes
        }
      }
    )
  ) @@ timeout(10.seconds) @@ diagnose(10.seconds) @@ nonFlaky
}
