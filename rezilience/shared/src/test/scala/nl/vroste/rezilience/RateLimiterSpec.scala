package nl.vroste.rezilience
import zio._
import zio.test.Assertion._
import zio.test.TestAspect.{ diagnose, nonFlaky, timeout }
import zio.test._

import java.time.Instant

object RateLimiterSpec extends ZIOSpecDefault {
  override def spec = suite("RateLimiter")(
    test("execute up to max calls immediately") {
      ZIO.scoped {
        for {
          rl    <- RateLimiter.make(10, 1.second)
          now   <- Clock.instant
          times <- ZIO.foreach((1 to 10).toList)(_ => rl(Clock.instant))
        } yield assert(times)(forall(equalTo(now)))
      }
    },
    test("is not affected by stream chunk size") {
      ZIO.scoped {
        for {
          rl            <- RateLimiter.make(10, 1.second)
          now           <- Clock.instant
          times1        <-
            ZIO.foreachPar((1 to 5).toList)(_ => rl(Clock.instant))
          secondCallFib <- ZIO.foreachPar((1 to 15).toList)(_ => rl(Clock.instant).fork)
          _             <- TestClock.adjust(1.second)
          times2        <- ZIO.foreach(secondCallFib)(_.join)

          times = times1 ++ times2

        } yield assert(times.filter(_ == now))(hasSize(equalTo(10)))
      }
    },
    test("succeed with the result of the call") {
      ZIO.scoped {
        for {
          rl     <- RateLimiter.make(10, 1.second)
          result <- rl(ZIO.succeed(3))
        } yield assert(result)(equalTo(3))
      }
    },
    test("fail with the result of a failed call") {
      ZIO.scoped {
        for {
          rl     <-
            RateLimiter.make(10, 1.second)
          result <- rl(ZIO.fail(None)).either
        } yield assert(result)(isLeft(isNone))
      }
    },
    test("continue after a failed call") {
      ZIO.scoped {
        for {
          rl <- RateLimiter.make(10, 1.second)
          _  <- rl(ZIO.fail(None)).either
          _  <- rl(ZIO.succeed(3))
        } yield assertCompletes
      }
    },
    test("holds back up calls after the max") {
      ZIO.scoped {
        for {
          rl    <- RateLimiter.make(10, 1.second)
          now   <- Clock.instant
          fib   <- ZIO.foreach((1 to 20).toList)(_ => rl(Clock.instant)).fork
          _     <- TestClock.adjust(1.second)
          later <- Clock.instant
          times <- fib.join
        } yield assert(times.take(10))(forall(equalTo(now))) && assert(times.drop(10))(
          forall(isGreaterThan(now) && isLessThanEqualTo(later))
        )
      }
    },
    test("will interrupt the effect when a call is interrupted") {
      ZIO.scoped {
        for {
          rl          <- RateLimiter.make(10, 1.second)
          latch       <- Promise.make[Nothing, Unit]
          interrupted <- Promise.make[Nothing, Unit]
          fib         <- rl((latch.succeed(()) *> ZIO.never).onInterrupt(interrupted.succeed(()))).fork
          _           <- latch.await
          _           <- fib.interrupt
          _           <- interrupted.await
        } yield assertCompletes
      }
    },
    test("will not start execution of an effect when it is interrupted before getting its turn to execute") {
      ZIO.scoped {
        for {
          rl           <- RateLimiter.make(1, 1.second)
          count        <- Ref.make(0)
          _            <- rl(ZIO.unit)
          fib          <- rl(count.set(1)).fork
          interruption <- fib.interrupt.fork
          _            <- interruption.join
          c            <- count.get
        } yield assert(c)(equalTo(0))
      }
    },
    test("will wait for interruption to complete of an effect that is already executing") {
      ZIO.scoped {
        for {
          rl                <- RateLimiter.make(1, 1.second)
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
    test("will make effects wait for interrupted effects to pass through the rate limiter") {
      ZIO.scoped {
        for {
          rl <- RateLimiter.make(1, 1.second)
          _  <- rl(ZIO.unit)               // Execute one
          f1 <- rl(ZIO.unit).fork
          _  <- TestClock.adjust(1.second) // This ensures that the second RL call is in the stream before we interrupt
          _  <- f1.interrupt

          // This one will have to wait 1 seconds
          fib               <- rl(Clock.instant).fork
          _                 <- TestClock.adjust(1.second)
          lastExecutionTime <- fib.join
        } yield assert(lastExecutionTime)(equalTo(Instant.ofEpochSecond(2)))
      }
    },
    test("will not include interrupted effects in the throttling") {
      val rate = 10
      ZIO.scoped {
        for {
          rl       <- RateLimiter.make(rate, 1.second)
          latch    <- Promise.make[Nothing, Unit]
          latched  <- Ref.make(0)
          continue <- Promise.make[Nothing, Unit]
          _        <- ZIO.replicateZIO(rate) {
                        rl {
                          ZIO.whenZIO(latched.updateAndGet(_ + 1).map(_ == rate))(latch.succeed(())) *>
                            continue.await
                        }.fork
                      }
          _        <- latch.await
          // Now we have 10 in progress. Now submit another bunch. We can interrupt these
          fibers   <- ZIO.replicateZIO(1000) {
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
  ) @@ timeout(10.seconds) @@ diagnose(10.seconds) @@ nonFlaky
}
