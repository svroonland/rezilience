package nl.vroste.rezilience

import nl.vroste.rezilience.Bulkhead.MetricSettings
import zio.metrics.{ Metric, MetricLabel }
import zio.test.Assertion._
import zio.test.TestAspect.{ nonFlaky, timed, timeout }
import zio.test._
import zio.{ durationInt, Promise, Ref, ZIO }

object BulkheadSpec extends ZIOSpecDefault {

  sealed trait Error

  case object MyCallError extends Error

  case object MyNotFatalError extends Error

  def spec = suite("Bulkhead")(
    test("executes calls immediately") {
      ZIO.scoped {
        for {
          bulkhead <- Bulkhead.make(10)
          p        <- Promise.make[Nothing, Unit]
          _        <- bulkhead(p.succeed(()))
          _        <- p.await
        } yield assertCompletes
      }
    },
    test("executes up to the max nr of calls immediately") {
      val max = 10
      ZIO.scoped {
        for {
          bulkhead       <- Bulkhead.make(max)
          p              <- Promise.make[Nothing, Unit]
          callsCompleted <- Ref.make(0)
          calls          <- ZIO.foreachPar(1 to max)(_ => p.await *> bulkhead(callsCompleted.updateAndGet(_ + 1))).fork
          _              <- p.succeed(())
          results        <- calls.join
        } yield assert(results)(hasSameElements((1 to max).toList))
      }
    },
    test("holds back more calls than the max") {
      val max = 20
      ZIO.scoped {
        for {
          bulkhead         <- Bulkhead.make(max)
          callsCompleted   <- Ref.make(0)
          calls            <-
            ZIO
              .foreachParDiscard(1 to max + 2)(_ =>
                bulkhead(callsCompleted.updateAndGet(_ + 1) *> ZIO.sleep(2.seconds))
              )
              .withParallelism(100)
              .fork
          _                <- TestClock.adjust(1.second)
          nrCallsCompleted <- callsCompleted.get
          _                <- TestClock.adjust(3.second)
          _                <- calls.join
        } yield assert(nrCallsCompleted)(equalTo(max))
      }
    },
    test("queues up to the queue limit and then reject calls") {
      val max        = 10
      val queueLimit = 5

      ZIO.scoped {
        for {
          bulkhead      <- Bulkhead.make(max, queueLimit)
          p             <- Promise.make[Nothing, Unit]
          maxInFlight   <- Promise.make[Nothing, Unit]
          callsInFlight <- Ref.make(0)
          // Enqueue 10, we expect 10 in flight
          calls         <- ZIO
                             .foreachParDiscard(1 to max) { _ =>
                               bulkhead {
                                 for {
                                   nrCallsInFlight <- callsInFlight.updateAndGet(_ + 1)
                                   _               <- maxInFlight.succeed(()).when(nrCallsInFlight >= max)
                                   _               <- p.await
                                 } yield ()
                               }
                             }
                             .withParallelismUnbounded
                             .fork
          _             <- maxInFlight.await raceFirst calls.join
          // Enqueue 6 more, of which one will fail
          failure       <- Promise.make[Nothing, Unit]
          calls2        <- ZIO
                             .foreachPar(1 to queueLimit + 1)(i =>
                               bulkhead(ZIO.unit).tapError(_ => failure.succeed(())).orElseFail(i).either
                             )
                             .withParallelismUnbounded
                             .fork
          // We expect one failure
          _             <- failure.await
          _             <- p.succeed(())
          _             <- calls.join
          results       <- calls2.join
        } yield assert(results.filter(_.isLeft))(hasSize(equalTo(1)))
      }
    },
    test("will interrupt the effect when a call is interrupted") {
      ZIO.scoped {
        for {
          bulkhead    <- Bulkhead.make(10)
          latch       <- Promise.make[Nothing, Unit]
          interrupted <- Promise.make[Nothing, Unit]
          fib         <- bulkhead((latch.succeed(()) *> ZIO.never).onInterrupt(interrupted.succeed(()))).fork
          _           <- latch.await
          _           <- fib.interrupt
          _           <- interrupted.await
        } yield assertCompletes
      }
    },
    suite("Bulkhead with metrics")(
      test("has correct metrics after interruption while started") {
        for {
          labels         <- ZIO.randomWith(_.nextUUID).map(uuid => Set(MetricLabel("test_id", uuid.toString)))
          bulkhead       <-
            Bulkhead.make(10, 5, Some(MetricSettings(labels)))
          latch          <- Promise.make[Nothing, Unit]
          interrupted    <- Promise.make[Nothing, Unit]
          fib            <- bulkhead((latch.succeed(()) *> ZIO.never).onInterrupt(interrupted.succeed(()))).fork
          _              <- latch.await
          _              <- fib.interrupt
          _              <- interrupted.await
          // TODO it's a histogram, so check that
          metricEnqueued <- Metric.counter("rezilience_circuit_breaker_calls_success").tagged(labels).value
        } yield assertCompletes
      }
//      test("emits correct currently in flight metrics") {
//        withMetricsCollection { onMetrics =>
//          Bulkhead
//            .make(10, 5)
//            .flatMap(Bulkhead.addMetrics(_, onMetrics, metricsInterval = 1.second))
//            .use { bulkhead =>
//              for {
//                latch1   <- Promise.make[Nothing, Unit]
//                latch2   <- Promise.make[Nothing, Unit]
//                continue <- Promise.make[Nothing, Unit]
//                _        <- TestClock.adjust(1.second)
//                fib      <- bulkhead(latch1.succeed(()) *> continue.await).fork
//                _        <- latch1.await
//                _        <- TestClock.adjust(1.second)
//                fib2     <- bulkhead(latch2.succeed(()) *> continue.await).fork
//                _        <- latch2.await
//                _        <- TestClock.adjust(1.second)
//                _        <- continue.succeed(())
//                _        <- TestClock.adjust(1.second)
//                _        <- fib.join
//                _        <- fib2.join
//              } yield ()
//            }
//        } { (metrics, _) =>
//          UIO(
//            assertTrue(metrics.map(_.currentlyInFlight) == Chunk(0L, 1, 2, 0, 0)) &&
//              assertTrue(metrics.reduce(_ + _).inFlight.getMaxValue == 2L)
//          )
//        }
//      }
//    )
    )
  ) @@ nonFlaky @@ timeout(120.seconds) @@ timed
}
