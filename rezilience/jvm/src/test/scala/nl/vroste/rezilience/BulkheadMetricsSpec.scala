package nl.vroste.rezilience

import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect.nonFlaky
import zio.test._
import zio.test.environment.TestClock
import zio.{ Chunk, Promise, Ref, UIO, ZIO }

object BulkheadMetricsSpec extends DefaultRunnableSpec {
  override def spec = suite("Bulkhead")(
    suite("preserves Bulkhead behavior")(
      testM("will interrupt the effect when a call is interrupted") {
        Bulkhead.make(10, 5).flatMap(Bulkhead.makeWithMetrics(_, _ => UIO.unit)).use { bulkhead =>
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
        withMetricsCollection { onMetrics =>
          for {
            fib <- Bulkhead
                     .make(10, 5)
                     .flatMap(
                       Bulkhead
                         .makeWithMetrics(_, onMetrics, metricsInterval = 5.second)
                     )
                     .use { rl =>
                       rl(ZIO.sleep(4.seconds))
                     }
                     .fork
            _   <- TestClock.adjust(4.seconds)
            _   <- fib.join
          } yield ()
        } { (metrics, _) =>
          assertM(UIO(metrics.reduce(_ + _)))(hasField("maxInFlight", _.inFlight.getMaxValue, equalTo(1L)))
        }
      },
      testM("emits metrics at the interval") {
        withMetricsCollection { onMetrics =>
          Bulkhead
            .make(10, 50)
            .flatMap(
              Bulkhead
                .makeWithMetrics(
                  _,
                  onMetrics,
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
        }((metrics, _) => assertM(UIO(metrics))(hasSize(equalTo(3))))
      },
      testM("can sum metrics") {
        withMetricsCollection { onMetrics =>
          Bulkhead
            .make(10, 50)
            .flatMap(
              Bulkhead
                .makeWithMetrics(
                  _,
                  onMetrics,
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
        } { (metrics, _) =>
          assertM(UIO(metrics.reduce(_ + _)))(hasField("interval", _.interval, equalTo(2500.millis)))
        }
      },
      testM("emits correct currently in flight metrics") {
        withMetricsCollection { onMetrics =>
          Bulkhead
            .make(10, 5)
            .flatMap(Bulkhead.makeWithMetrics(_, onMetrics, metricsInterval = 1.second))
            .use { bulkhead =>
              for {
                latch1   <- Promise.make[Nothing, Unit]
                latch2   <- Promise.make[Nothing, Unit]
                continue <- Promise.make[Nothing, Unit]
                _        <- TestClock.adjust(1.second)
                fib      <- bulkhead(latch1.succeed(()) *> continue.await).fork
                _        <- latch1.await
                _        <- TestClock.adjust(1.second)
                fib2     <- bulkhead(latch2.succeed(()) *> continue.await).fork
                _        <- latch2.await
                _        <- TestClock.adjust(1.second)
                _        <- continue.succeed(())
                _        <- TestClock.adjust(1.second)
                _        <- fib.join
                _        <- fib2.join
              } yield ()
            }
        } { (metrics, _) =>
          UIO(
            assertTrue(metrics.map(_.currentlyInFlight) == Chunk(0L, 1, 2, 0, 0)) &&
              assertTrue(metrics.reduce(_ + _).inFlight.getMaxValue == 2L)
          )
        }
      }
    ) @@ nonFlaky
  )

  def withMetricsCollection[R, E, A](
    f: (BulkheadMetrics => UIO[Unit]) => ZIO[R, E, A]
  )(assert: (Chunk[BulkheadMetrics], A) => ZIO[R, E, TestResult]): ZIO[R, E, TestResult] = for {
    metricsRef <- Ref.make[Chunk[BulkheadMetrics]](Chunk.empty)
    result     <- f(m => metricsRef.update(_ :+ m))
    metrics    <- metricsRef.get
    testResult <- assert(metrics, result)
  } yield testResult
}
