package nl.vroste.rezilience

import nl.vroste.rezilience.CircuitBreaker.{ CircuitBreakerOpen, State }
import zio._
import zio.test.Assertion._
import zio.test.TestAspect.{ nonFlaky, timeout, withLiveClock, withLiveConsole }
import zio.test._

case class PrintFriendlyDuration(duration: Duration) extends AnyVal {
  def +(that: PrintFriendlyDuration) = PrintFriendlyDuration(duration + that.duration)

  override def toString: String = s"${duration.asScala.toMillis} ms"
}

object FailureRateTrippingStrategySpec extends ZIOSpecDefault {
  sealed trait Error
  case object MyCallError     extends Error
  case object MyNotFatalError extends Error

  val randomListOfIntervals: Gen[Any, List[PrintFriendlyDuration]] = Gen.int(0, 5).flatMap {
    Gen.listOfN(_) {
      Gen.finiteDuration(min = 100.millis, max = 10.seconds).map(PrintFriendlyDuration(_))
    }
  }

  override def spec =
    suite("Failure rate tripping strategy")(
      test("does not trip initially") {
        for {
          strategy   <- TrippingStrategy.failureRate()
          shouldTrip <- strategy.shouldTrip
        } yield assert(shouldTrip)(isFalse)
      } @@ nonFlaky,
      test("does not trip when all calls are successful") {
        check(
          Gen.double(0.0, 1.0),                     // Failure threshold
          Gen.finiteDuration(1.second, 10.minutes), // Sample duration
          Gen.int(1, 10),                           // Min throughput
          randomListOfIntervals                     // Intervals
        ) { case (rate, sampleDuration, minThroughput, callIntervals) =>
          ZIO.scoped {
            for {
              strategy         <- TrippingStrategy.failureRate(rate, sampleDuration, minThroughput)
              shouldTripChecks <- ZIO.foreach(callIntervals) { d =>
                                    (TestClock.adjust(d.duration) <* strategy.onSuccess) *> strategy.shouldTrip
                                  }
            } yield assert(shouldTripChecks)(forall(isFalse))
          }
        }
      },
      test("only trips after the sample period") {
        val rate           = 0.5
        val sampleDuration = 400.millis
        val minThroughput  = 5

        val strategy = TrippingStrategy.failureRate(rate, sampleDuration, minThroughput, nrSampleBuckets = 10)
        for {
          cb <- CircuitBreaker.make[String](strategy, resetPolicy = Schedule.fixed(5.seconds))
          // Make a succeeding and a failing call 4 times every 100 ms
          _  <- {
            cb(ZIO.unit) *> cb(ZIO.fail("Oh Oh")).either *> TestClock.adjust(150.millis)
          }.repeat(Schedule.recurs(3))
          // Next call should fail
          _  <- TestClock.adjust(50.millis)
          r  <- cb(UIO.succeed(println("Succeeding call that should fail fast"))).exit
        } yield assert(r)(fails(equalTo(CircuitBreakerOpen)))
      } @@ nonFlaky,
      test("does not trip if the failure rate stays below the threshold") {
        val rate           = 0.7
        val sampleDuration = 100.millis
        val minThroughput  = 5

        val strategy = TrippingStrategy.failureRate(rate, sampleDuration, minThroughput, nrSampleBuckets = 10)
        for {
          cb <- CircuitBreaker.make[String](
                  strategy,
                  Schedule.fixed(5.seconds),
                  onStateChange = state => ZIO.succeed(println(s"CB state changed to ${state}"))
                )
          // Make a succeeding and a failing call 4 times every 100 ms
          _  <- {
            cb(ZIO.unit) *> cb(ZIO.fail("Oh Oh")).either
          }.repeat(Schedule.spaced(10.millis) && Schedule.recurs(10))
        } yield assertCompletes
      } @@ withLiveClock @@ nonFlaky @@ TestAspect.parallel,
      test("does not trip after resetting") {
        val rate           = 0.5
        val sampleDuration = 400.millis
        val minThroughput  = 5

        val strategy = TrippingStrategy.failureRate(rate, sampleDuration, minThroughput, nrSampleBuckets = 10)

        ZIO.scoped {
          (for {
            stateChanges <- Queue.unbounded[State]
            cb           <- CircuitBreaker
                              .make[String](strategy, Schedule.fixed(1.seconds), onStateChange = stateChanges.offer(_).ignore)
          } yield (stateChanges, cb)).flatMap { case (stateChanges, cb) =>
            def expectState(s: State) = stateChanges.take.filterOrDieMessage(_ == s)(s"Expected state ${s}")

            def makeCall[R, A](f: ZIO[R, String, A]) = cb(f)

            for {
              // Make a succeeding and a failing call 4 times every 100 ms
              _ <- (makeCall(ZIO.unit) *> makeCall(ZIO.fail("Oh Oh")).either)
                     .repeat(Schedule.spaced(150.millis) && Schedule.recurs(3))
              _ <- expectState(State.Open)

              // Next call should fail
              _ <- makeCall(ZIO.unit).flip

              // Wait for HalfOpen state
              _ <- expectState(State.HalfOpen)

              // Succeed a call to go back to Closed state
              _ <- makeCall(ZIO.unit)
              _ <- expectState(State.Closed)

              // Make some failed calls but less than minThroughput
              _ <- makeCall(ZIO.fail("Oh oh")).either.repeat(Schedule.recurs(3))

              // Next call should should go through
              _ <- makeCall(ZIO.unit)
            } yield assertCompletes
          }
        }
      } @@ withLiveClock @@ withLiveConsole @@ nonFlaky,
      test("trips only after the sample duration has expired and all calls fail") {
        val nrSampleBuckets = 10

        check(
          Gen.double(0.1, 1.0),                                                   // Failure threshold
          Gen.finiteDuration(1.second, 10.minutes).map(PrintFriendlyDuration(_)), // Sample duration
          Gen.int(1, 10),                                                         // Min throughput
          randomListOfIntervals
        ) { case (rate, sampleDuration, minThroughput, callIntervals) =>
          val totalTimes = callIntervals.scan(PrintFriendlyDuration(0.seconds))(_ + _).tail
          val totalCalls = callIntervals.map(_ => 1).scan(0)(_ + _).tail

          ZIO.scoped {
            for {
              strategy         <- TrippingStrategy.failureRate(rate, sampleDuration.duration, minThroughput, nrSampleBuckets)
              shouldTripChecks <-
                ZIO.foreach((callIntervals zip totalTimes) zip totalCalls) { case ((d, totalTime), totalCalls) =>
                  for {
                    _         <- TestClock.adjust(d.duration)
                    _         <- strategy.onFailure
                    wouldTrip <- strategy.shouldTrip
                  } yield
                    if (wouldTrip)
                      totalTime.duration >= sampleDuration.duration && totalCalls >= minThroughput
                    else true
                }
            } yield assert(shouldTripChecks)(forall(isTrue))
          }
        }
      }
    ) @@ timeout(240.seconds) @@ TestAspect.repeats(10)
}
