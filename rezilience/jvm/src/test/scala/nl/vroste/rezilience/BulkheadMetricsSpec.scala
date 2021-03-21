package nl.vroste.rezilience

import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect.nonFlaky
import zio.test._
import zio.test.environment.TestClock
import zio.{ Promise, Ref, UIO, ZIO }

object BulkheadMetricsSpec extends DefaultRunnableSpec {
  override def spec = suite("Bulkhead")(
    suite("preserves Bulkhead behavior")(
      testM("will interrupt the effect when a call is interrupted") {
        BulkheadPlatformSpecificObj.makeWithMetrics(10, 5, _ => UIO.unit).use { bulkhead =>
          for {
            latch       <- Promise.make[Nothing, Unit]
            interrupted <- Promise.make[Nothing, Unit]
            fib         <- bulkhead((latch.succeed(()) *> ZIO.never).onInterrupt(interrupted.succeed(()))).fork
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
          metricsRef <- Promise.make[Nothing, BulkheadMetrics]
          fib        <- BulkheadPlatformSpecificObj
                          .makeWithMetrics(10, 5, onMetrics = metricsRef.succeed, metricsInterval = 5.second)
                          .use { rl =>
                            rl(ZIO.sleep(4.seconds))
                          }
                          .fork
          _          <- TestClock.adjust(4.seconds)
          _          <- fib.join
          metrics    <- metricsRef.await

        } yield assert(metrics)(hasField("maxInFlight", _.inFlight.getMaxValue, equalTo(1L)))
      },
      testM("emits metrics at the interval") {
        for {
          metricsRef <- Ref.make(Vector.empty[BulkheadMetrics])
          _          <- BulkheadPlatformSpecificObj
                          .makeWithMetrics(
                            10,
                            5,
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
          metricsRef <- Ref.make(BulkheadMetrics.empty)
          _          <- BulkheadPlatformSpecificObj
                          .makeWithMetrics(
                            10,
                            5,
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
    ) @@ nonFlaky
  )
}
