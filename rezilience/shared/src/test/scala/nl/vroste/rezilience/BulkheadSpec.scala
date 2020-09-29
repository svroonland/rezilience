package nl.vroste.rezilience

import zio.test.Assertion._
import zio.test._
import zio.Promise
import zio.Ref
import zio.duration._
import zio.ZIO
import zio.test.environment.{ testEnvironment, TestClock, TestEnvironment }
import nl.vroste.rezilience.Bulkhead.BulkheadRejection
import zio.UIO
import zio.test.TestAspect.{ diagnose, nonFlaky, timeout }

object BulkheadSpec extends DefaultRunnableSpec {

  sealed trait Error

  case object MyCallError extends Error

  case object MyNotFatalError extends Error

  val env = testEnvironment ++ TestConfig.live(100, 100, 200, 1000)

  override def runner: TestRunner[TestEnvironment, Any] = TestRunner(TestExecutor.default(env))

  def spec = suite("Bulkhead")(
    testM("executes calls immediately") {
      Bulkhead.make(10).use { bulkhead =>
        for {
          p <- Promise.make[Nothing, Unit]
          _ <- bulkhead(p.succeed(()))
          _ <- p.await
        } yield assertCompletes
      }
    },
    testM("executes up to the max nr of calls immediately") {
      val max = 10
      Bulkhead.make(max).use { bulkhead =>
        for {
          p              <- Promise.make[Nothing, Unit]
          callsCompleted <- Ref.make(0)
          calls          <- ZIO.foreachPar(1 to max)(_ => p.await *> bulkhead(callsCompleted.updateAndGet(_ + 1))).fork
          _              <- p.succeed(())
          results        <- calls.join
        } yield assert(results)(hasSameElements((1 to max).toList))
      }
    },
    testM("holds back more calls than the max") {
      val max = 20
      Bulkhead.make(max).use { bulkhead =>
        for {
          callsCompleted   <- Ref.make(0)
          calls            <-
            ZIO
              .foreachPar_(1 to max + 2)(_ => bulkhead(callsCompleted.updateAndGet(_ + 1) *> ZIO.sleep(2.seconds)))
              .fork
          _                <- TestClock.adjust(1.second)
          nrCallsCompleted <- callsCompleted.get
          _                <- TestClock.adjust(3.second)
          _                <- calls.join
        } yield assert(nrCallsCompleted)(equalTo(max))
      }
    },
    testM("queues up to the queue limit and then reject calls") {
      val max        = 10
      val queueLimit = 5

      Bulkhead.make(max, queueLimit).use { bulkhead =>
        for {
          p             <- Promise.make[Nothing, Unit]
          maxInFlight   <- Promise.make[Nothing, Unit]
          callsInFlight <- Ref.make(0)
          calls         <- ZIO
                             .foreachPar_(1 to max + queueLimit) { i =>
                               bulkhead {
                                 for {
//                                   _               <- UIO(println(s"Executing bulkhead ${i}"))
                                   nrCallsInFlight <- callsInFlight.updateAndGet(_ + 1)
                                   _               <- maxInFlight.succeed(()).when(nrCallsInFlight >= max)
                                   _               <- p.await
                                 } yield ()
                               }.tapError(e => bulkhead.metrics.flatMap(m => UIO(println(s"Call ${i} failed! ${e}, ${m}"))))
                             }
                             .fork
          _             <- maxInFlight.await race calls.join
//          _             <- UIO(println("Making the extraneous call"))
          result        <- bulkhead(ZIO.unit).either
          _             <- p.succeed(())
          _             <- calls.join
        } yield assert(result)(isLeft(equalTo(BulkheadRejection)))
      }
    },
    testM("will interrupt the effect when a call is interrupted") {
      Bulkhead.make(10).use { bulkhead =>
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
  ) @@ nonFlaky @@ timeout(60.seconds) @@ diagnose(60.seconds)
}
