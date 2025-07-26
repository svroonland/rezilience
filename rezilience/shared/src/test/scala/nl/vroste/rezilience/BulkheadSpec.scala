package nl.vroste.rezilience

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
          latch            <- waitForLatch
          calls            <-
            ZIO
              .foreachParDiscard(1 to max + 2)(_ =>
                bulkhead(callsCompleted.updateAndGet(_ + 1).flatMap { completed =>
                  latch.started.succeed(()).when(completed == max)
                } *> latch.latch.await)
              )
              .withParallelism(100)
              .fork
          _                <- latch.started.await
          nrCallsCompleted <- callsCompleted.get
          _                <- latch.latch.succeed(())
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
    test("can handle interrupts with another call enqueued") {
      ZIO.scoped {
        for {
          bulkhead     <- Bulkhead.make(10)
          waitForLatch <- waitForLatch
          e             = waitForLatch.effect
          started       = waitForLatch.started
          fibs         <- ZIO.replicateZIO(10)(bulkhead(e).fork)
          _            <- started.await
          fib2         <- bulkhead(e).fork
          _            <- ZIO.foreachDiscard(fibs)(_.interrupt)
          _            <- fib2.interrupt
        } yield assertCompletes
      }
    }
  ) @@ nonFlaky @@ timeout(120.seconds) @@ timed

  case class WaitForLatch(
    effect: ZIO[Any, Nothing, Unit],
    started: Promise[Nothing, Unit],
    latch: Promise[Nothing, Unit]
  )

  val waitForLatch: ZIO[Any, Nothing, WaitForLatch] = for {
    latch   <- Promise.make[Nothing, Unit]
    started <- Promise.make[Nothing, Unit]
    effect   = started.succeed(()) *> latch.await
  } yield WaitForLatch(effect, started, latch)
}
