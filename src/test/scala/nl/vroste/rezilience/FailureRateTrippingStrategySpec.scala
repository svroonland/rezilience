package nl.vroste.rezilience

import zio.duration._
import zio.test.Assertion._
import zio.test._
import zio.{ Schedule, ZIO }
import zio.random.Random
import zio.test.environment.TestClock
import nl.vroste.rezilience.CircuitBreaker.CircuitBreakerOpen
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
      // testM("does not trip initially") {
      //   TrippingStrategy.failureRate().use { strategy =>
      //     for {
      //       shouldTrip <- strategy.shouldTrip
      //     } yield assert(shouldTrip)(isFalse)
      //   }
      // },
      // testM("does not trip when all calls are successful") {
      //   checkM(
      //     Gen.double(0.0, 1.0),                     // Failure threshold
      //     Gen.finiteDuration(1.second, 10.minutes), // Sample duration
      //     Gen.int(1, 10),                           // Min throughput
      //     randomListOfIntervals                     // Intervals
      //   ) {
      //     case (rate, sampleDuration, minThroughput, callIntervals) =>
      //       TrippingStrategy.failureRate(rate, sampleDuration, minThroughput).use { strategy =>
      //         for {
      //           shouldTripChecks <- ZIO.foreach(callIntervals) { d =>
      //                                (TestClock.adjust(d.duration) <* strategy.onSuccess) *> strategy.shouldTrip
      //                              }
      //         } yield assert(shouldTripChecks)(forall(isFalse))
      //       }
      //   }
      // },
//       testM("trips only after the sample duration has expired and all calls fail") {
//         val nrSampleBuckets = 10

//         checkM(
//           // Gen.double(0.1, 1.0),                                                // Failure threshold
//           // Gen.finiteDuration(1.second, 10.minutes).map(PrintFriendlyDuration), // Sample duration
//           // Gen.int(1, 10),                                                      // Min throughput
//           Gen.const(0.1),
//           Gen.const(PrintFriendlyDuration(1.second)),
//           Gen.const(1),
//           // Gen.const(List(PrintFriendlyDuration(3685.millis)))
//           randomListOfIntervals
//         ) {
//           case (rate, sampleDuration, minThroughput, callIntervals) =>
// //          val sampleDuration = PrintFriendlyDuration(1.second)
// //          val minThroughput = 1
// //          val callIntervals = List(PrintFriendlyDuration(100.millis))
//             println(
//               s"Beginning test with failure rate ${rate}, duration ${sampleDuration}, minThroughput: ${minThroughput}"
//             )
//             val totalTimes = callIntervals.scan(PrintFriendlyDuration(0.seconds))(_ + _).tail
//             val totalCalls = callIntervals.map(_ => 1).scan(0)(_ + _).tail

//             TrippingStrategy.failureRate(rate, sampleDuration.duration, minThroughput, nrSampleBuckets).use {
//               strategy =>
//                 for {
//                   shouldTripChecks <- ZIO.foreach((callIntervals zip totalTimes) zip totalCalls) {
//                                        case ((d, totalTime), totalCalls) =>
//                                          println(s"Making failed call after ${d}, total time is ${d}")
//                                          for {
//                                            _ <- adjustClockInSteps(
//                                                  d.duration,
//                                                  sampleDuration.duration * (1.0 / nrSampleBuckets)
//                                                )
//                                            // After we adjust the clock, we should somehow wait for all the actions that should have been taken up to that point. Although this is
//                                            // fixed in ZIO RC 19, for now we have to actually let the system run for a bit
//                                            _         <- ZIO.sleep(50.millis).provideLayer(zio.clock.Clock.live)
//                                            _         <- strategy.onFailure
//                                            wouldTrip <- strategy.shouldTrip
//                                            //  _         = println(s"Woud trip ${wouldTrip}, total time = ${totalTime}")
//                                          } yield
//                                            if (wouldTrip)
//                                              totalTime.duration >= sampleDuration.duration && totalCalls >= minThroughput
//                                            else true
//                                      }
//                   _ = println(s"Should trip checks: ${shouldTripChecks}")
//                 } yield assert(shouldTripChecks)(forall(isTrue))
//             }
//         }

//       },
      // testM("trips only after the sample duration has expired and all calls fail (single)") {
      //   val nrSampleBuckets = 10

      //   val rate           = 0.1
      //   val sampleDuration = PrintFriendlyDuration(1.second)
      //   val minThroughput  = 1
      //   val callIntervals  = List(100.millis).map(PrintFriendlyDuration)
      //   println(
      //     s"Beginning test with failure rate ${rate}, duration ${sampleDuration}, minThroughput: ${minThroughput}"
      //   )
      //   val totalTimes = callIntervals.scan(PrintFriendlyDuration(0.seconds))(_ + _).tail
      //   val totalCalls = callIntervals.map(_ => 1).scan(0)(_ + _).tail

      //   TrippingStrategy.failureRate(rate, sampleDuration.duration, minThroughput, nrSampleBuckets).use {
      //     strategy =>
      //       for {
      //         shouldTripChecks <- ZIO.foreach((callIntervals zip totalTimes) zip totalCalls) {
      //                              case ((d, totalTime), totalCalls) =>
      //                                println(s"Making failed call after ${d}, total time is ${d}")
      //                                for {
      //                                  _ <- adjustClockInSteps(
      //                                        d.duration,
      //                                        sampleDuration.duration * (1.0 / nrSampleBuckets)
      //                                      )
      //                                  // After we adjust the clock, we should somehow wait for all the actions that should have been taken up to that point. Although this is
      //                                  // fixed in ZIO RC 19, for now we have to actually let the system run for a bit
      //                                  //  _         <- ZIO.sleep(50.millis).provideLayer(zio.clock.Clock.live)
      //                                  _         <- strategy.onFailure
      //                                  wouldTrip <- strategy.shouldTrip
      //                                  //  _         = println(s"Woud trip ${wouldTrip}, total time = ${totalTime}")
      //                                } yield
      //                                  if (wouldTrip)
      //                                    totalTime.duration >= sampleDuration.duration && totalCalls >= minThroughput
      //                                  else true
      //                            }
      //         _ = println(s"Should trip checks: ${shouldTripChecks}")
      //       } yield assert(shouldTripChecks)(forall(isTrue))
      //   }

      // } @@ TestAspect.repeat(Schedule.recurs(100)),
      testM("do what you'd expect in real time") {
        val rate           = 0.5
        val sampleDuration = 400.millis
        val minThroughput  = 5

        val strategy = TrippingStrategy.failureRate(rate, sampleDuration, minThroughput, nrSampleBuckets = 10)
        CircuitBreaker
          .make(strategy, Schedule.fixed(5.seconds), state => ZIO.effectTotal(println(s"CB state changed to ${state}")))
          .use {
            cb =>
              for {
                // Make a succeeding and a failing call 4 times every 100 ms
                _ <- {
                  cb.withCircuitBreaker(ZIO(println("Succeeding call"))) *>
                    cb.withCircuitBreaker(ZIO(println("Failing call")) *> ZIO.fail("Oh Oh")).either
                }.repeat(Schedule.fixed(150.millis) && Schedule.recurs(3))
                // Next call should fail
                _ <- ZIO.sleep(50.millis)
                r <- cb.withCircuitBreaker(ZIO(println("Succeeding call that should fail fast"))).run
              } yield assert(r)(fails(equalTo(CircuitBreakerOpen)))
          }
      }.provideSomeLayer(zio.clock.Clock.live),
      testM("does not trip after resetting") { ZIO.succeed(assertCompletes) }
    ) @@ TestAspect.timeout(1.minute)

  private def adjustClockInSteps(
    total: Duration,
    step: Duration
  ): ZIO[TestClock with zio.clock.Clock with zio.console.Console, Nothing, Unit] = {
    val nrSteps = Math.ceil(total.toMillis * 1.0 / step.toMillis).toInt
    println(s"Adjusting clocks in ${nrSteps} steps of ${step.toMillis} ms with total of ${total.toMillis} ms")
    (zio.clock.currentDateTime.flatMap(dt => zio.console.putStrLn("Time is now " + dt.toString)).ignore *> TestClock
      .adjust(step))
      .repeat(Schedule.recurs(nrSteps - 1))
      .unit &> ZIO.sleep(total)
  }
}
