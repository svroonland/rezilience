package nl.vroste.rezilience

import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect.nonFlaky
import zio.test._
import zio.test.environment.TestClock
import zio.{ Chunk, Promise, Ref, UIO, ZIO }

object RateLimiterMetricsSpec extends DefaultRunnableSpec {
  override def spec = suite("RateLimiter")(
    suite("preserves RateLimiter behavior")(
      testM("will interrupt the effect when a call is interrupted") {
        RateLimiter
          .make(10, 1.second)
          .flatMap(RateLimiterPlatformSpecificObj.addMetrics(_, _ => UIO.unit))
          .use { rl =>
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
    ),
    suite("metrics")(
      testM("emits metrics after use") {
        for {
          metricsRef <- Promise.make[Nothing, RateLimiterMetrics]
          _          <- RateLimiter
                          .make(10, 1.second)
                          .flatMap(
                            RateLimiterPlatformSpecificObj
                              .addMetrics(_, onMetrics = metricsRef.succeed, metricsInterval = 5.second)
                          )
                          .use { rl =>
                            rl(UIO.unit)
                          }
          metrics    <- metricsRef.await

        } yield assert(metrics)(hasField("tasksStarted", _.tasksStarted, equalTo(1L))) &&
          assert(metrics)(hasField("tasksEnqueued", _.tasksEnqueued, equalTo(1L)))
      },
      testM("will not increase tasksStarted for interrupted tasks") {
        checkM(Gen.int(1, 100)) { rateLimit =>
          withMetricsCollection { onMetrics =>
            for {
              startedCounter <- CountDownLatch.make(rateLimit)
              continue       <- Promise.make[Nothing, Unit]
              _              <- RateLimiter
                                  .make(rateLimit, 1.second)
                                  .flatMap(
                                    RateLimiterPlatformSpecificObj
                                      .addMetrics(_, onMetrics = onMetrics, metricsInterval = 1.second)
                                  )
                                  .use { rl =>
                                    for {
                                      fib1 <- ZIO
                                                .foreachPar_(1 to rateLimit)(_ => rl(startedCounter.countDown *> continue.await))
                                                .fork
                                      _    <- startedCounter.await
                                      fib2 <- rl(ZIO.debug("Starting 4th task") *> continue.await).fork
                                      _    <- TestClock.adjust(0.second)
                                      _    <- fib2.interrupt
                                      _    <- TestClock.adjust(1.second)
                                      _    <- continue.succeed(())
                                      _    <- fib1.join
                                    } yield ()
                                  }
            } yield ()
          } { (_, metrics, _) =>
            ZIO.succeed {
              // Assert that the extra task was actually enqueued in the RateLimiter before we interrupt it
              assert(metrics)(hasField("tasksEnqueued", _.tasksEnqueued, equalTo(rateLimit + 1L))) &&
              assert(metrics)(hasField("tasksStarted", _.tasksStarted, equalTo(rateLimit.toLong))) &&
              assert(metrics)(hasField("currentlyEnqueued", _.currentlyEnqueued, equalTo(0L)))
            }
          }
        }
      } @@ nonFlaky(1),
      testM("emits metrics at the interval") {
        for {
          metricsRef <- Ref.make(Vector.empty[RateLimiterMetrics])
          _          <- RateLimiter
                          .make(10, 1.second)
                          .flatMap(
                            RateLimiterPlatformSpecificObj
                              .addMetrics(
                                _,
                                onMetrics = m => metricsRef.update(_ :+ m),
                                metricsInterval = 1.second
                              )
                          )
                          .use { rl =>
                            for {
                              _ <- rl(UIO.unit).fork.repeatN(100)
                              _ <- TestClock.adjust(1.second)
                              _ <- TestClock.adjust(1.second)
                              _ <- TestClock.adjust(500.millis)
                            } yield ()
                          }
          metrics    <- metricsRef.get
        } yield assert(metrics)(hasSize(equalTo(3)))
      },
      testM("can sum metrics") {
        for {
          metricsRef <- Ref.make(RateLimiterMetrics.empty)
          _          <- RateLimiter
                          .make(10, 1.second)
                          .flatMap(
                            RateLimiterPlatformSpecificObj
                              .addMetrics(
                                _,
                                onMetrics = m => metricsRef.update(_ + m),
                                metricsInterval = 1.second
                              )
                          )
                          .use { rl =>
                            for {
                              _ <- rl(UIO.unit).fork.repeatN(100)
                              _ <- TestClock.adjust(1.second)
                              _ <- TestClock.adjust(1.second)
                              _ <- TestClock.adjust(500.millis)
                            } yield ()
                          }
          metrics    <- metricsRef.get
        } yield assert(metrics)(hasField("interval", _.interval, equalTo(2500.millis)))
      }
    ) @@ nonFlaky(1) // TODO specific tests
  )

  def withMetricsCollection[R, E, A](
    f: (RateLimiterMetrics => UIO[Unit]) => ZIO[R, E, A]
  )(assert: (Chunk[RateLimiterMetrics], RateLimiterMetrics, A) => ZIO[R, E, TestResult]): ZIO[R, E, TestResult] = for {
    metricsRef <- Ref.make[Chunk[RateLimiterMetrics]](Chunk.empty)
    result     <- f(m => metricsRef.update(_ :+ m))
    metrics    <- metricsRef.get
    testResult <- assert(metrics, metrics.reduce(_ + _), result)
  } yield testResult
}
