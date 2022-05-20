package nl.vroste.rezilience

import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect.nonFlaky
import zio.test._
import zio.test.environment.TestClock
import zio.{ Promise, Ref, UIO, ZIO }

object RateLimiterMetricsSpec extends DefaultRunnableSpec {
  override def spec = suite("RateLimiter")(
    suite("preserves RateLimiter behavior")(
      testM("will interrupt the effect when a call is interrupted") {
        RateLimiter
          .make(10, 1.second)
          .flatMap(RateLimiterPlatformSpecificObj.adMetrics(_, _ => UIO.unit))
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
                              .adMetrics(_, onMetrics = metricsRef.succeed, metricsInterval = 5.second)
                          )
                          .use { rl =>
                            rl(UIO.unit)
                          }
          metrics    <- metricsRef.await

        } yield assert(metrics)(hasField("tasksStarted", _.tasksStarted, equalTo(1L))) &&
          assert(metrics)(hasField("tasksEnqueued", _.tasksEnqueued, equalTo(1L)))
      },
      testM("emits metrics at the interval") {
        for {
          metricsRef <- Ref.make(Vector.empty[RateLimiterMetrics])
          _          <- RateLimiter
                          .make(10, 1.second)
                          .flatMap(
                            RateLimiterPlatformSpecificObj
                              .adMetrics(
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
                              .adMetrics(
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
    ) @@ nonFlaky
  )
}
