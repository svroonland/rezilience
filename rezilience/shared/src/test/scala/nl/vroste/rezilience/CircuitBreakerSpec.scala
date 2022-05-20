package nl.vroste.rezilience

import nl.vroste.rezilience.CircuitBreaker.{ State, StateChange }
import zio.duration._
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestClock
import zio.{ clock, Chunk, Promise, Queue, Ref, Schedule, UIO, ZIO }

object CircuitBreakerSpec extends DefaultRunnableSpec {
  sealed trait Error
  case object MyCallError     extends Error
  case object MyNotFatalError extends Error

  // TODO add generator based checks with different nr of parallel calls to check
  // for all kinds of race conditions
  def spec = suite("CircuitBreaker")(
    testM("lets successful calls through") {
      CircuitBreaker.withMaxFailures(10, Schedule.exponential(1.second)).use { cb =>
        for {
          _ <- cb(ZIO.unit).repeat(Schedule.recurs(20))
        } yield assertCompletes
      }
    },
    testM("fails fast after max nr failures calls") {
      CircuitBreaker
        .withMaxFailures(100, Schedule.exponential(1.second))
        .use { cb =>
          for {
            _      <-
              ZIO.foreachPar_(1 to 105)(_ => cb(ZIO.fail(MyCallError)).either.tapCause(c => ZIO.debug(c)))
            result <- cb(ZIO.fail(MyCallError)).either
          } yield assert(result)(isLeft(equalTo(CircuitBreaker.CircuitBreakerOpen)))
        }
    } @@ TestAspect.diagnose(20.seconds),
    testM("ignore failures that should not be considered a failure") {
      val isFailure: PartialFunction[Error, Boolean] = {
        case MyNotFatalError => false
        case _: Error        => true
      }

      CircuitBreaker
        .withMaxFailures(3, Schedule.exponential(1.second), isFailure)
        .use { cb =>
          for {
            _      <- ZIO.foreach_(1 to 3)(_ => cb(ZIO.fail(MyNotFatalError))).either
            result <- cb(ZIO.fail(MyCallError)).either
          } yield assert(result)(isLeft(not(equalTo(CircuitBreaker.CircuitBreakerOpen))))
        }
    },
    testM("reset to closed state after reset timeout") {
      (for {
        stateChanges      <- Queue.unbounded[State].toManaged_
        cb                <- CircuitBreaker.withMaxFailures(10, Schedule.exponential(1.second))
        stateChangesQueue <- cb.stateChanges
        _                 <- ZStream
                               .fromQueue(stateChangesQueue)
                               .map(_.to)
                               .tap(stateChanges.offer)
                               .runDrain
                               .forkManaged
      } yield (stateChanges, cb)).use { case (stateChanges, cb) =>
        for {
          _ <- ZIO.foreach_(1 to 10)(_ => cb(ZIO.fail(MyCallError)).either)
          _ <- stateChanges.take
          _ <- TestClock.adjust(2.second)
          _ <- stateChanges.take
          _ <- cb(ZIO.unit)
        } yield assertCompletes
      }
    },
    testM("retry exponentially") {
      (for {
        stateChanges      <- Queue.unbounded[State].toManaged_
        cb                <- CircuitBreaker.withMaxFailures(3, Schedule.exponential(base = 1.second, factor = 2.0))
        stateChangesQueue <- cb.stateChanges
        _                 <- ZStream
                               .fromQueue(stateChangesQueue)
                               .map(_.to)
                               .tap(stateChanges.offer)
                               .runDrain
                               .forkManaged
      } yield (stateChanges, cb)).use { case (stateChanges, cb) =>
        for {
          _  <- ZIO.foreach_(1 to 3)(_ => cb(ZIO.fail(MyCallError)).either)
          s1 <- stateChanges.take // Open
          _  <- TestClock.adjust(1.second)
          s2 <- stateChanges.take // HalfOpen
          _  <- cb(ZIO.fail(MyCallError)).either
          s3 <- stateChanges.take // Open again
          s4 <- stateChanges.take.timeout(1.second) <& TestClock.adjust(1.second)
          _  <- TestClock.adjust(1.second)
          s5 <- stateChanges.take
          _  <- cb(ZIO.unit)
          s6 <- stateChanges.take
        } yield assert(s1)(equalTo(State.Open)) &&
          assert(s2)(equalTo(State.HalfOpen)) &&
          assert(s3)(equalTo(State.Open)) &&
          assert(s4)(isNone) &&
          assert(s5)(equalTo(State.HalfOpen)) &&
          assert(s6)(equalTo(State.Closed))
      }
    },
    testM("reset the exponential timeout after a Closed-Open-HalfOpen-Closed") {
      (for {
        stateChanges      <- Queue.unbounded[State].toManaged_
        cb                <- CircuitBreaker.withMaxFailures(3, Schedule.exponential(base = 1.second, factor = 2.0))
        stateChangesQueue <- cb.stateChanges
        _                 <- ZStream
                               .fromQueue(stateChangesQueue)
                               .map(_.to)
                               .tap(stateChanges.offer)
                               .runDrain
                               .forkManaged
      } yield (stateChanges, cb)).use { case (stateChanges, cb) =>
        for {
          _ <- ZIO.foreach_(1 to 3)(_ => cb(ZIO.fail(MyCallError)).either)
          _ <- stateChanges.take // Open
          _ <- TestClock.adjust(1.second)
          _ <- stateChanges.take // HalfOpen

          _ <- cb(ZIO.fail(MyCallError)).either
          _ <- stateChanges.take // Open again, this time with double reset timeout

          _ <- TestClock.adjust(2.second)
          _ <- stateChanges.take // HalfOpen

          _ <- cb(ZIO.unit)
          _ <- stateChanges.take // Closed again

          _ <- ZIO.foreach_(1 to 3)(_ => cb(ZIO.fail(MyCallError)).either)
          _ <- stateChanges.take // Open

          // Reset time should have re-initialized again
          _  <- TestClock.adjust(1.second)
          s1 <- stateChanges.take // HalfOpen
        } yield assert(s1)(equalTo(State.HalfOpen))
      }
    },
    suite("metrics")(
      testM("can sum metrics") {
        for {
          metricsRef <- Ref.make(CircuitBreakerMetrics.empty)
          _          <- CircuitBreaker
                          .withMaxFailures(3)
                          .flatMap(
                            CircuitBreaker
                              .addMetrics(_, onMetrics = m => metricsRef.update(_ + m), metricsInterval = 1.second)
                          )
                          .use { cb =>
                            for {
                              fib <- ZIO.foreachPar_(1 to 100)(_ => cb(UIO.unit)).fork
                              _   <- TestClock.adjust(1.second)
                              _   <- TestClock.adjust(1.second)
                              _   <- fib.join
                            } yield ()
                          }
          metrics    <- metricsRef.get
        } yield assert(metrics)(hasField("interval", _.interval, equalTo(2000.millis))) &&
          assert(metrics)(hasField("succeededCalls", _.succeededCalls, equalTo(100L)))
      },
      testM("emits metrics after use") {
        for {
          metricsRef <- Promise.make[Nothing, CircuitBreakerMetrics]
          _          <- CircuitBreaker
                          .withMaxFailures(3)
                          .flatMap(
                            CircuitBreaker
                              .addMetrics(_, onMetrics = metricsRef.succeed, metricsInterval = 5.second)
                          )
                          .use { cb =>
                            cb(UIO.unit)
                          }
          metrics    <- metricsRef.await

        } yield assert(metrics)(hasField("succeededCalls", _.succeededCalls, equalTo(1L)))
      },
      testM("emits metrics periodically") {
        for {
          metricsRef <- Ref.make[Chunk[CircuitBreakerMetrics]](Chunk.empty)
          _          <- CircuitBreaker
                          .withMaxFailures(3)
                          .flatMap(
                            CircuitBreaker
                              .addMetrics(_, onMetrics = m => metricsRef.update(_ :+ m), metricsInterval = 5.second)
                          )
                          .use { cb =>
                            for {
                              _ <- cb(UIO.unit)
                              _ <- TestClock.adjust(5.second)
                              _ <- cb(UIO.unit)
                              _ <- TestClock.adjust(5.second)
                            } yield ()
                          }
          metrics    <- metricsRef.get

        } yield assert(metrics)(hasSize(equalTo(3)))

      },
      testM("emits successful and failed calls in each metrics interval") {
        for {
          metricsRef <- Ref.make[Chunk[CircuitBreakerMetrics]](Chunk.empty)
          _          <- CircuitBreaker
                          .withMaxFailures(3)
                          .flatMap(
                            CircuitBreaker
                              .addMetrics(_, onMetrics = m => metricsRef.update(_ :+ m), metricsInterval = 5.second)
                          )
                          .use { cb =>
                            for {
                              _ <- cb(UIO.unit)
                              _ <- TestClock.adjust(5.second)
                              _ <- cb(ZIO.fail("Failed")).either
                              _ <- TestClock.adjust(5.second)
                            } yield ()
                          }
          metrics    <- metricsRef.get

        } yield assertTrue(metrics.map(_.succeededCalls) == Chunk(1L, 0, 0)) &&
          assertTrue(metrics.map(_.failedCalls) == Chunk(0L, 1, 0))
      },
      testM("records state changes") {
        withMetricsCollection { onMetrics =>
          for {
            now <- clock.instant
            _   <-
              CircuitBreaker
                .withMaxFailures(10, Schedule.exponential(1.second))
                .flatMap(
                  CircuitBreaker
                    .addMetrics(_, onMetrics, metricsInterval = 5.second)
                )
                .use { cb =>
                  for {
                    _ <- ZIO.foreach_(1 to 10)(_ => cb(ZIO.fail(MyCallError)).either)
                    _ <- TestClock.adjust(1.second)
                    _ <- TestClock.adjust(1.second)
                    _ <- cb(ZIO.unit)
                    _ <- TestClock.adjust(1.second)
                  } yield ()
                }
          } yield now
        } { (metrics, now) =>
          UIO(
            assertTrue(
              metrics.map(_.stateChanges).flatten == Chunk(
                StateChange(State.Closed, State.Open, now),
                StateChange(State.Open, State.HalfOpen, now.plusSeconds(1)),
                StateChange(State.HalfOpen, State.Closed, now.plusSeconds(2))
              )
            )
          )
        }
      },
      testM("records time of last reset") {
        withMetricsCollection { onMetrics =>
          for {
            now <- clock.instant
            _   <-
              CircuitBreaker
                .withMaxFailures(10, Schedule.exponential(1.second))
                .flatMap(
                  CircuitBreaker
                    .addMetrics(_, onMetrics, metricsInterval = 5.second)
                )
                .use { cb =>
                  for {
                    _ <- ZIO.foreach_(1 to 10)(_ => cb(ZIO.fail(MyCallError)).either)
                    _ <- TestClock.adjust(1.second)
                    _ <- TestClock.adjust(1.second)
                    _ <- cb(ZIO.unit)
                    _ <- TestClock.adjust(1.second)
                  } yield ()
                }
          } yield now
        } { (metrics, now) =>
          UIO(
            assertTrue(metrics.reduce(_ + _).lastResetTime.get == now.plusSeconds(2))
          )
        }
      }
    )
  ) @@ TestAspect.timeout(30.seconds) @@ TestAspect.nonFlaky

  def withMetricsCollection[R, E, A](
    f: (CircuitBreakerMetrics => UIO[Unit]) => ZIO[R, E, A]
  )(assert: (Chunk[CircuitBreakerMetrics], A) => ZIO[R, E, TestResult]): ZIO[R, E, TestResult] = for {
    metricsRef <- Ref.make[Chunk[CircuitBreakerMetrics]](Chunk.empty)
    result     <- f(m => metricsRef.update(_ :+ m))
    metrics    <- metricsRef.get
    testResult <- assert(metrics, result)
  } yield testResult
}
