package nl.vroste.rezilience

import nl.vroste.rezilience.CircuitBreaker.{ CircuitBreakerOpen, State, WrappedError }
import zio._
import zio.metrics.{ MetricKey, MetricLabel, MetricState }
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test.TestAspect.{ nonFlaky, withLiveRandom }
import zio.test._

object CircuitBreakerSpec extends ZIOSpecDefault {
  sealed trait Error
  case object MyCallError     extends Error
  case object MyNotFatalError extends Error

  val isFailure: PartialFunction[Error, Boolean] = {
    case MyNotFatalError => false
    case _: Error        => true
  }

  // TODO add generator based checks with different nr of parallel calls to check
  // for all kinds of race conditions
  override def spec = suite("CircuitBreaker")(
    test("lets successful calls through") {
      for {
        cb <- CircuitBreaker.withMaxFailures(10, Schedule.exponential(1.second))
        _  <- cb(ZIO.unit).repeat(Schedule.recurs(20))
      } yield assertCompletes
    },
    test("fails fast after max nr failures calls") {

      for {
        cb     <- CircuitBreaker.withMaxFailures(100, Schedule.exponential(1.second))
        _      <-
          ZIO.foreachParDiscard(1 to 105)(_ => cb(ZIO.fail(MyCallError)).either.tapErrorCause(c => ZIO.debug(c)))
        result <- cb(ZIO.fail(MyCallError)).either
      } yield assert(result)(isLeft(equalTo(CircuitBreaker.CircuitBreakerOpen)))
    } @@ TestAspect.diagnose(20.seconds),
    test("ignore failures that should not be considered a failure") {
      for {
        cb     <- CircuitBreaker.withMaxFailures(3, Schedule.exponential(1.second), isFailure)
        _      <- cb(ZIO.fail(MyNotFatalError)).either.repeatN(3)
        result <- cb(ZIO.fail(MyCallError)).either
      } yield assertTrue(
        result.left.toOption.get != CircuitBreaker.CircuitBreakerOpen
      )
    },
    test("reset to closed state after reset timeout") {
      for {
        cb                <- CircuitBreaker.withMaxFailures(
                               10,
                               Schedule.exponential(1.second)
                             )
        stateChangesQueue <- cb.stateChanges
        stateChanges      <- Queue.unbounded[State]
        _                 <- ZStream.fromQueue(stateChangesQueue).map(_.to).tap(stateChanges.offer).runDrain.forkScoped
        _                 <- ZIO.foreachDiscard(1 to 10)(_ => cb(ZIO.fail(MyCallError)).either)
        _                 <- stateChanges.take
        _                 <- TestClock.adjust(3.second)
        _                 <- stateChanges.take
        _                 <- cb(ZIO.unit)
      } yield assertCompletes
    },
    test("retry exponentially") {
      (for {
        cb                <- CircuitBreaker.withMaxFailures(
                               3,
                               Schedule.exponential(base = 1.second, factor = 2.0)
                             )
        stateChangesQueue <- cb.stateChanges
        stateChanges      <- Queue.unbounded[State]
        _                 <- ZStream.fromQueue(stateChangesQueue).map(_.to).tap(stateChanges.offer).runDrain.forkScoped
        _                 <- ZIO.foreachDiscard(1 to 3)(_ => cb(ZIO.fail(MyCallError)).either)
        s1                <- stateChanges.take // Open
        _                 <- TestClock.adjust(1.second)
        s2                <- stateChanges.take // HalfOpen
        _                 <- cb(ZIO.fail(MyCallError)).either
        s3                <- stateChanges.take // Open again
        s4                <- stateChanges.take.timeout(1.second) <& TestClock.adjust(1.second)
        _                 <- TestClock.adjust(1.second)
        s5                <- stateChanges.take
        _                 <- cb(ZIO.unit)
        s6                <- stateChanges.take
      } yield assert(s1)(equalTo(State.Open)) &&
        assert(s2)(equalTo(State.HalfOpen)) &&
        assert(s3)(equalTo(State.Open)) &&
        assert(s4)(isNone) &&
        assert(s5)(equalTo(State.HalfOpen)) &&
        assert(s6)(equalTo(State.Closed))).tapErrorCause(result => ZIO.debug(result))
    },
    test("have not stuck in HalfOpen if some defect happens") {
      for {
        cb <- CircuitBreaker.withMaxFailures(1)
        _  <- cb(ZIO.fail(MyCallError)).either
        s1 <- cb.currentState // Open
        _  <- TestClock.adjust(1.second)
        s2 <- cb.currentState // HalfOpen
        _  <- cb(ZIO.dieMessage("Boom")).catchAllDefect(_ => ZIO.unit)
        s3 <- cb.currentState // Back to Open
      } yield assert(s1)(equalTo(State.Open)) &&
        assert(s2)(equalTo(State.HalfOpen)) &&
        assert(s3)(equalTo(State.Open))
    },
    test("allow next call on interrupt in HalfOpen") {
      checkAll(
        Gen.fromIterable(
          List(
            ZIO.unit              -> State.Closed,
            ZIO.fail(MyCallError) -> State.Open
          )
        )
      ) { case (finalCall, finalState) =>
        for {
          cb <- CircuitBreaker.withMaxFailures(1)
          _  <- cb(ZIO.fail(MyCallError)).either
          s1 <- cb.currentState // Open
          _  <- TestClock.adjust(1.second)
          s2 <- cb.currentState // HalfOpen
          _  <- cb(ZIO.interrupt).exit
          s3 <- cb.currentState // Still HalfOpen
          _  <- cb(finalCall).exit
          s4 <- cb.currentState
        } yield assertTrue(
          s1 == State.Open,
          s2 == State.HalfOpen,
          s3 == State.HalfOpen,
          s4 == finalState
        )
      }
    },
    test("reset the exponential timeout after a Closed-Open-HalfOpen-Closed") {
      for {
        cb                <- CircuitBreaker.withMaxFailures(
                               3,
                               Schedule.exponential(base = 1.second, factor = 2.0)
                             )
        stateChangesQueue <- cb.stateChanges
        stateChanges      <- Queue.unbounded[State]
        _                 <- ZStream.fromQueue(stateChangesQueue).map(_.to).tap(stateChanges.offer).runDrain.forkScoped
        _                 <- ZIO.foreachDiscard(1 to 3)(_ => cb(ZIO.fail(MyCallError)).either)
        _                 <- stateChanges.take // Open
        _                 <- TestClock.adjust(1.second)
        _                 <- stateChanges.take // HalfOpen

        _ <- cb(ZIO.fail(MyCallError)).either
        _ <- stateChanges.take // Open again, this time with double reset timeout

        _ <- TestClock.adjust(2.second)
        _ <- stateChanges.take // HalfOpen

        _ <- cb(ZIO.unit)
        _ <- stateChanges.take // Closed again

        _ <- ZIO.foreachDiscard(1 to 3)(_ => cb(ZIO.fail(MyCallError)).either)
        _ <- stateChanges.take // Open

        // Reset time should have re-initialized again
        _  <- TestClock.adjust(1.second)
        s1 <- stateChanges.take // HalfOpen
      } yield assert(s1)(equalTo(State.HalfOpen))
    },
    test("reset to Closed after Half-Open on success")(
      for {
        cb      <- CircuitBreaker.withMaxFailures(5, Schedule.exponential(2.second), isFailure)
        intRef  <- Ref.make(0)
        error1  <- cb(ZIO.fail(MyNotFatalError)).flip
        errors  <- ZIO.replicateZIO(5)(cb(ZIO.fail(MyCallError)).flip)
        _       <- TestClock.adjust(1.second)
        error3  <- cb(intRef.update(_ + 1)).flip // no backend calls here
        _       <- TestClock.adjust(1.second)
        _       <- cb(intRef.update(_ + 1))
        _       <- cb(intRef.update(_ + 1))
        nrCalls <- intRef.get
      } yield assertTrue(error1.asInstanceOf[WrappedError[Error]].error == MyNotFatalError) &&
        assertTrue(errors.forall(_.asInstanceOf[WrappedError[Error]].error == MyCallError)) &&
        assertTrue(error3 == CircuitBreakerOpen) &&
        assertTrue(nrCalls == 2)
    ),
    test("reset to Closed after Half-Open on error if isFailure=false") {
      for {
        cb      <- CircuitBreaker.withMaxFailures(5, Schedule.exponential(2.second), isFailure)
        intRef  <- Ref.make(0)
        errors  <- ZIO.replicateZIO(5)(cb(ZIO.fail(MyCallError)).flip)
        _       <- TestClock.adjust(1.second)
        error1  <- cb(intRef.update(_ + 1)).flip // no backend calls here
        _       <- TestClock.adjust(1.second)
        error2  <- cb(ZIO.fail(MyNotFatalError)).flip
        _       <- cb(intRef.update(_ + 1))
        nrCalls <- intRef.get
      } yield assertTrue(errors.forall(_.asInstanceOf[WrappedError[Error]].error == MyCallError)) &&
        assertTrue(error1 == CircuitBreakerOpen) &&
        assertTrue(error2.asInstanceOf[WrappedError[Error]].error == MyNotFatalError) &&
        assertTrue(nrCalls == 1)
    },
    suite("metrics")(
      test("has suitable initial metric values") {
        import Metrics._
        for {
          labels             <- ZIO.randomWith(_.nextUUID).map(uuid => Set(MetricLabel("test_id", uuid.toString)))
          _                  <- CircuitBreaker
                                  .withMaxFailures(3, metricLabels = Some(labels))
          metricState        <- metricStateKey(labels).current[MetricState.Gauge]
          metricStateChanges <- metricStateChangesKey(labels).current[MetricState.Counter]
          metricSuccess      <- metricSuccessKey(labels).current[MetricState.Counter]
          metricFailed       <- metricFailedKey(labels).current[MetricState.Counter]
          metricRejected     <- metricRejectedKey(labels).current[MetricState.Counter]
        } yield assertTrue(
          metricSuccess.get.count == 0 &&
            metricFailed.get.count == 0 &&
            metricState.get.value == 0.0 &&
            metricStateChanges.get.count == 0 &&
            metricRejected.get.count == 0
        )
      },
      test("tracks successful and failed calls") {
        import Metrics._
        for {
          labels        <- ZIO.randomWith(_.nextUUID).map(uuid => Set(MetricLabel("test_id", uuid.toString)))
          cb            <- CircuitBreaker
                             .withMaxFailures(3, metricLabels = Some(labels))
          _             <- cb(ZIO.unit)
          _             <- cb(ZIO.fail("Failed")).either
          metricSuccess <- metricSuccessKey(labels).current[MetricState.Counter]
          metricFailed  <- metricFailedKey(labels).current[MetricState.Counter]
        } yield assertTrue(
          metricSuccess.get.count == 1 &&
            metricFailed.get.count == 1
        )
      },
      test("records state changes") {
        import Metrics._
        for {
          labels <- ZIO.randomWith(_.nextUUID).map(uuid => Set(MetricLabel("test_id", uuid.toString)))
          cb     <- CircuitBreaker
                      .withMaxFailures(10, Schedule.exponential(1.second), metricLabels = Some(labels))

          _                  <- ZIO.foreachDiscard(1 to 10)(_ => cb(ZIO.fail(MyCallError)).either)
          _                  <- TestClock.adjust(0.second)
          stateAfterFailures <- metricStateKey(labels).current[MetricState.Gauge]
          _                  <- TestClock.adjust(1.second)
          stateAfterReset    <- metricStateKey(labels).current[MetricState.Gauge]
          _                  <- TestClock.adjust(1.second)
          _                  <- cb(ZIO.unit)
          _                  <- TestClock.adjust(1.second)
          stateChanges       <- metricStateChangesKey(labels).current[MetricState.Counter]
          stateFinal         <- metricStateKey(labels).current[MetricState.Gauge]
        } yield assertTrue(
          stateChanges.get.count == 3 &&
            stateAfterFailures.get.value == 2.0 &&
            stateAfterReset.get.value == 1.0 &&
            stateFinal.get.value == 0.0
        )
      }
    ) @@ withLiveRandom
  ) @@ nonFlaky

  object Metrics {

    val metricStateKey = MetricKey.gauge("rezilience_circuit_breaker_state").tagged(_: Set[MetricLabel])

    val metricStateChangesKey =
      MetricKey.counter("rezilience_circuit_breaker_state_changes").tagged(_: Set[MetricLabel])

    val metricSuccessKey =
      MetricKey.counter("rezilience_circuit_breaker_calls_success").tagged(_: Set[MetricLabel])

    val metricFailedKey =
      MetricKey.counter("rezilience_circuit_breaker_calls_failure").tagged(_: Set[MetricLabel])

    val metricRejectedKey =
      MetricKey.counter("rezilience_circuit_breaker_calls_rejected").tagged(_: Set[MetricLabel])

    implicit class MetricKeyOps[Type](val metricKey: MetricKey[Type]) extends AnyVal {
      def current[Out <: MetricState[Type]]: Task[Option[Out]] =
        ZIO.metrics.map {
          _.metrics.collectFirst {
            case metricPair if metricPair.metricKey == metricKey =>
              metricPair.metricState.asInstanceOf[Out]
          }
        }
    }
  }

}
