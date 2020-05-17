package nl.vroste.rezilience

import zio.duration._
import zio.test.Assertion._
import zio.test._
import zio.{ Schedule, ZIO }
import zio.random.Random
import zio.test.environment.TestClock
//import zio.test.environment.TestClock
//import zio.{ Queue, Schedule, ZIO }

case class PrintFriendlyDuration(duration: Duration) extends AnyVal {
  def +(that: PrintFriendlyDuration) = PrintFriendlyDuration(duration + that.duration)

  override def toString: String = s"${duration.asScala.toMillis} ms"
}

object FailureRateTrippingStrategySpec extends DefaultRunnableSpec {
  sealed trait Error
  case object MyCallError     extends Error
  case object MyNotFatalError extends Error

  val randomListOfIntervals: Gen[Random, List[PrintFriendlyDuration]] = Gen.int(0, 10).flatMap {
    Gen.listOfN(_) {
      Gen.finiteDuration(min = 100.millis, max = 10.seconds).map(PrintFriendlyDuration)
    }
  }

  // TODO add generator based checks with different nr of parallel calls to check
  // for all kinds of race conditions
  def spec =
    suite("Failure rate tripping strategy")(
      testM("does not trip initially") {
        TrippingStrategy.failureRate().use { strategy =>
          for {
            shouldTrip <- strategy.shouldTrip
          } yield assert(shouldTrip)(isFalse)
        }
      },
      testM("does not trip when all calls are successful") {
        checkM(
          Gen.double(0.0, 1.0),                     // Failure threshold
          Gen.finiteDuration(1.second, 10.minutes), // Sample duration
          Gen.int(1, 10),                           // Min throughput
          randomListOfIntervals                     // Intervals
        ) {
          case (rate, sampleDuration, minThroughput, callIntervals) =>
            TrippingStrategy.failureRate(rate, sampleDuration, minThroughput).use { strategy =>
              for {
                shouldTripChecks <- ZIO.foreach(callIntervals) { d =>
                                     (TestClock.adjust(d.duration) <* strategy.onSuccess) *> strategy.shouldTrip
                                   }
              } yield assert(shouldTripChecks)(forall(isFalse))
            }
        }
      },
      testM("trips only after the sample duration has expired and all calls fail") {
        val nrSampleBuckets = 10

        checkM(
          Gen.double(0.1, 1.0),                                                // Failure threshold
          Gen.finiteDuration(1.second, 10.minutes).map(PrintFriendlyDuration), // Sample duration
          Gen.int(1, 10),                                                      // Min throughput
          randomListOfIntervals
          // Gen.const(0.1),
          // Gen.const(PrintFriendlyDuration(1.second)),
          // Gen.const(1),
          // Gen.const(List(PrintFriendlyDuration(3685.millis)))
        ) {
          case (rate, sampleDuration, minThroughput, callIntervals) =>
//          val sampleDuration = PrintFriendlyDuration(1.second)
//          val minThroughput = 1
//          val callIntervals = List(PrintFriendlyDuration(100.millis))
            println(
              s"Beginning test with failure rate ${rate}, duration ${sampleDuration}, minThroughput: ${minThroughput}"
            )
            val totalTimes = callIntervals.scan(PrintFriendlyDuration(0.seconds))(_ + _).tail
            val totalCalls = callIntervals.map(_ => 1).scan(0)(_ + _).tail

            TrippingStrategy.failureRate(rate, sampleDuration.duration, minThroughput, nrSampleBuckets).use {
              strategy =>
                for {
                  shouldTripChecks <- ZIO.foreach((callIntervals zip totalTimes) zip totalCalls) {
                                       case ((d, totalTime), totalCalls) =>
                                         println(s"Making failed call after ${d}, total time is ${d}")
                                         for {
                                           _ <- adjustClockInSteps(
                                                 d.duration,
                                                 sampleDuration.duration * (1.0 / nrSampleBuckets)
                                               )
                                           // After we adjust the clock, we should somehow wait for all the actions that should have been taken up to that point. Although this is
                                           // fixed in ZIO RC 19, for now we have to actually let the system run for a bit
                                           _         <- ZIO.sleep(50.millis).provideLayer(zio.clock.Clock.live)
                                           _         <- strategy.onFailure
                                           wouldTrip <- strategy.shouldTrip
                                           //  _         = println(s"Woud trip ${wouldTrip}, total time = ${totalTime}")
                                         } yield
                                           if (wouldTrip)
                                             totalTime.duration >= sampleDuration.duration && totalCalls >= minThroughput
                                           else true
                                     }
                  _ = println(s"Should trip checks: ${shouldTripChecks}")
                } yield assert(shouldTripChecks)(forall(isTrue))
            }
        }

      },
      testM("trips only after the sample duration has expired and all calls fail (single)") {
        val nrSampleBuckets = 10

        val rate           = 0.1
        val sampleDuration = PrintFriendlyDuration(1.second)
        val minThroughput  = 3
        val callIntervals  = List(500.millis, 2300.millis, 3700.millis).map(PrintFriendlyDuration)
        println(
          s"Beginning test with failure rate ${rate}, duration ${sampleDuration}, minThroughput: ${minThroughput}"
        )
        val totalTimes = callIntervals.scan(PrintFriendlyDuration(0.seconds))(_ + _).tail
        val totalCalls = callIntervals.map(_ => 1).scan(0)(_ + _).tail

        TrippingStrategy.failureRate(rate, sampleDuration.duration, minThroughput, nrSampleBuckets).use {
          strategy =>
            for {
              shouldTripChecks <- ZIO.foreach((callIntervals zip totalTimes) zip totalCalls) {
                                   case ((d, totalTime), totalCalls) =>
                                     println(s"Making failed call after ${d}, total time is ${d}")
                                     for {
                                       _ <- adjustClockInSteps(
                                             d.duration,
                                             sampleDuration.duration * (1.0 / nrSampleBuckets)
                                           )
                                       // After we adjust the clock, we should somehow wait for all the actions that should have been taken up to that point. Although this is
                                       // fixed in ZIO RC 19, for now we have to actually let the system run for a bit
                                       _         <- ZIO.sleep(50.millis).provideLayer(zio.clock.Clock.live)
                                       _         <- strategy.onFailure
                                       wouldTrip <- strategy.shouldTrip
                                       //  _         = println(s"Woud trip ${wouldTrip}, total time = ${totalTime}")
                                     } yield
                                       if (wouldTrip)
                                         totalTime.duration >= sampleDuration.duration && totalCalls >= minThroughput
                                       else true
                                 }
              _ = println(s"Should trip checks: ${shouldTripChecks}")
            } yield assert(shouldTripChecks)(forall(isTrue))
        }

      },
      testM("does not trip after resetting") { ZIO.succeed(assertCompletes) }
    ) @@ TestAspect.timeout(1.minute)

  private def adjustClockInSteps(total: Duration, step: Duration) = {
    val nrSteps = Math.ceil(total.toMillis * 1.0 / step.toMillis).toInt
    // println(s"Adjusting clocks in ${nrSteps} steps of ${step.toMillis} ms with total of ${total.toMillis} ms")
    TestClock.adjust(step).repeat(Schedule.recurs(nrSteps - 1))
  }
}
