package nl.vroste.rezilience

import zio.test._
import zio.duration._
import zio.{ clock, Promise, Ref, Schedule, UIO, ZIO }
import zio.clock.Clock
import zio.random.Random
import zio.test.Assertion._
import zio.test.environment.TestClock

object RateLimiterMetricsSpec extends DefaultRunnableSpec {
  override def spec = suite("RateLimiter")(
    suite("preserves RateLimiter behavior")(
      testM("will interrupt the effect when a call is interrupted") {
        RateLimiterPlatformSpecificObj.make(10, 1.second, _ => UIO.unit).use { rl =>
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
          _          <- RateLimiterPlatformSpecificObj
                          .make(10, 1.second, onMetrics = metricsRef.succeed, metricsInterval = 5.second)
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
          _          <- RateLimiterPlatformSpecificObj
                          .make(
                            10,
                            1.second,
                            onMetrics = m => metricsRef.update(_ :+ m),
                            metricsInterval = 1.second
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
          _          <- RateLimiterPlatformSpecificObj
                          .make(
                            10,
                            1.second,
                            onMetrics = m => metricsRef.update(_ + m),
                            metricsInterval = 1.second
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
    ),
    suite("metrics live")(
      testM("emits metrics") {
        RateLimiterPlatformSpecificObj
          .make(10, 1.second, onMetrics = metrics => UIO(println(metrics)), metricsInterval = 5.second)
          .use { rl =>
            for {
              _ <- rl(clock.instant.flatMap(now => UIO(println(now)))).fork
                     .repeat(Schedule.fixed(100.millis))
                     .fork
              _ <- ZIO.sleep(3.seconds)
            } yield assertCompletes
          }
      }
    ).provideCustomLayer(Clock.live ++ Random.live)
  )
}
