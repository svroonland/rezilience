package nl.vroste.rezilience

import zio.test.Assertion._
import zio.test._
import zio.Promise
import zio.Ref
import zio.duration._
import zio.ZIO
import zio.test.environment.TestClock
import nl.vroste.rezilience.Bulkhead.BulkheadRejection
import zio.UIO

object BulkheadSpec extends DefaultRunnableSpec {

  sealed trait Error

  case object MyCallError extends Error

  case object MyNotFatalError extends Error

  def spec = suite("Bulkhead")(
    testM("executes calls immediately") {
      Bulkhead.make(10).use { bulkhead =>
        for {
          p <- Promise.make[Nothing, Unit]
          _ <- bulkhead.call(p.succeed(()))
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
          calls          <- ZIO.foreachPar(1 to max)(_ => p.await *> bulkhead.call(callsCompleted.updateAndGet(_ + 1))).fork
          _              <- p.succeed(())
          results        <- calls.join
        } yield assert(results)(hasSameElements((1 to max).toList))
      }
    },
    testM("holds back more calls than the max") {
      val max = 10
      Bulkhead.make(max).use { bulkhead =>
        for {
          p                <- Promise.make[Nothing, Unit]
          callsCompleted   <- Ref.make(0)
          calls            <- ZIO.foreachPar(1 to max + 2)(_ => bulkhead.call(callsCompleted.updateAndGet(_ + 1) *> p.await)).fork
          _                <- TestClock.adjust(1.second)
          _                <- calls.interrupt
          nrCallsCompleted <- callsCompleted.get
        } yield assert(nrCallsCompleted)(equalTo(max))
      }
    },
    testM("queues up to the queue limit") {
      val max        = 10
      val queueLimit = 5

      Bulkhead.make(max, queueLimit).use {
        bulkhead =>
          for {
            p             <- Promise.make[Nothing, Unit]
            maxInFlight   <- Promise.make[Nothing, Unit]
            callsInFlight <- Ref.make(0)
            calls <- ZIO
                      .foreachPar(1 to max + queueLimit) { _ =>
                        bulkhead.call {
                          (for {
                            nrCallsInFlight <- callsInFlight.updateAndGet(_ + 1)
                            _               = println(s"Nr calls in flight: ${nrCallsInFlight}")
                            _               <- maxInFlight.succeed(()).when(nrCallsInFlight == max)
                            _               <- p.await
                          } yield ())
                        }.tapError(e => UIO(println(s"Call failed! ${e}")))
                          .either
                      }
                      .fork
            _      <- maxInFlight.await.raceFirst(calls.join)
            _      = println("Max is in flight")
            result <- bulkhead.call(ZIO.unit).either
            _      <- calls.interrupt
          } yield assert(result)(equalTo(Left(BulkheadRejection)))
      }
    }
  )
}
