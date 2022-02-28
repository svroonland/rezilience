package nl.vroste.rezilience

import zio.test.Assertion._
import zio.test._
import zio._
import nl.vroste.rezilience.CircuitBreaker.CircuitBreakerOpen
import nl.vroste.rezilience.CircuitBreaker.State
import zio.test.TestAspect.{ diagnose, nonFlaky, timeout }

case class PrintFriendlyDuration(duration: Duration) extends AnyVal {
  def +(that: PrintFriendlyDuration) = PrintFriendlyDuration(duration + that.duration)

  override def toString: String = s"${duration.asScala.toMillis} ms"
}

object FailureRateTrippingStrategySpec extends DefaultRunnableSpec {
  sealed trait Error
  case object MyCallError     extends Error
  case object MyNotFatalError extends Error

  val randomListOfIntervals: Gen[Random, List[PrintFriendlyDuration]] = Gen.int(0, 5).flatMap {
    Gen.listOfN(_) {
      Gen.finiteDuration(min = 100.millis, max = 10.seconds).map(PrintFriendlyDuration(_))
    }
  }

  // Smaller number of repeats because of using the live clock
  val env = testEnvironment ++ TestConfig.live(10, 100, 200, 1000)

  override def runner: TestRunner[TestEnvironment, Any] = TestRunner(TestExecutor.default(env))

  def spec =
    suite("Failure rate tripping strategy")(
      test("does not trip initially") {
        TrippingStrategy.failureRate().use { strategy =>
          for {
            shouldTrip <- strategy.shouldTrip
          } yield assert(shouldTrip)(isFalse)
        }
      } @@ nonFlaky,
      test("does not trip when all calls are successful") {
        check(
          Gen.double(0.0, 1.0),                     // Failure threshold
          Gen.finiteDuration(1.second, 10.minutes), // Sample duration
          Gen.int(1, 10),                           // Min throughput
          randomListOfIntervals                     // Intervals
        ) { case (rate, sampleDuration, minThroughput, callIntervals) =>
          TrippingStrategy.failureRate(rate, sampleDuration, minThroughput).use { strategy =>
            for {
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
        CircuitBreaker
          .make[String](strategy, resetPolicy = Schedule.fixed(5.seconds))
          .use { cb =>
            for {
              // Make a succeeding and a failing call 4 times every 100 ms
              _ <- {
                cb(ZIO.unit) *> cb(ZIO.fail("Oh Oh")).either
              }.repeat(Schedule.spaced(150.millis) && Schedule.recurs(3))
              // Next call should fail
              _ <- ZIO.sleep(50.millis)
              r <- cb(UIO(println("Succeeding call that should fail fast"))).exit
            } yield assert(r)(fails(equalTo(CircuitBreakerOpen)))
          }
      }.provideSomeLayer(Clock.live) @@ nonFlaky,
      test("does not trip if the failure rate stays below the threshold") {
        val rate           = 0.7
        val sampleDuration = 400.millis
        val minThroughput  = 5

        val strategy = TrippingStrategy.failureRate(rate, sampleDuration, minThroughput, nrSampleBuckets = 10)
        CircuitBreaker
          .make[String](
            strategy,
            Schedule.fixed(5.seconds),
            onStateChange = state => ZIO.succeed(println(s"CB state changed to ${state}"))
          )
          .use { cb =>
            for {
              // Make a succeeding and a failing call 4 times every 100 ms
              _ <- {
                cb(ZIO.unit) *> cb(ZIO.fail("Oh Oh")).either
              }.repeat(Schedule.spaced(150.millis) && Schedule.recurs(10))
            } yield assertCompletes
          }
      }.provideSomeLayer(Clock.live) @@ nonFlaky,
      test("does not trip after resetting") {
        val rate           = 0.5
        val sampleDuration = 400.millis
        val minThroughput  = 5

        val strategy = TrippingStrategy.failureRate(rate, sampleDuration, minThroughput, nrSampleBuckets = 10)

        (for {
          stateChanges <- Queue.unbounded[State].toManaged
          cb           <- CircuitBreaker
                            .make[String](strategy, Schedule.fixed(1.seconds), onStateChange = stateChanges.offer(_).ignore)
        } yield (stateChanges, cb)).use { case (stateChanges, cb) =>
          def expectState(s: State)                = stateChanges.take.filterOrDieMessage(_ == s)(s"Expected state ${s}")
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
      }.provideSomeLayer(Clock.live ++ Console.live) @@ nonFlaky,
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

          TrippingStrategy.failureRate(rate, sampleDuration.duration, minThroughput, nrSampleBuckets).use { strategy =>
            for {
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
    ) @@ timeout(120.seconds) @@ diagnose(120.seconds)
}
