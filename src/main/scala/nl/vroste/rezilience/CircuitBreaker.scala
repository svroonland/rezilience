package nl.vroste.rezilience
import zio.clock.Clock
import zio.duration.Duration
import zio.stream.ZStream
import zio._

object CircuitBreaker {
  sealed trait CircuitBreakerCallError[+E]
  case object CircuitBreakerOpen       extends CircuitBreakerCallError[Nothing]
  case class WrappedError[E](error: E) extends CircuitBreakerCallError[E]

  // TODO how strict on max failures when you fire 200 calls simultaneously?
  // TODO reset with exponential backoff
  trait Service {
    def call[R, E, A](f: ZIO[R, E, A]): ZIO[R, CircuitBreakerCallError[E], A]
  }

  def make(
    maxFailures: Int,
    resetTimeout: Duration,
    onStateChange: State => UIO[Unit] = _ => ZIO.unit
  ): ZManaged[Clock, Nothing, Service] =
    for {
      state          <- Ref.make[State](Closed).toManaged_
      halfOpenSwitch <- Ref.make[Boolean](true).toManaged_
      nrFailedCalls  <- Ref.make[Int](0).toManaged_
      resetRequests  <- ZQueue.bounded[Unit](1).toManaged_
      _ <- ZStream
            .fromQueue(resetRequests)
            .tap(_ => ZIO(println(s"Got reset request, delaying with ${resetTimeout}")))
            .mapM(_ => ZIO.unit.delay(resetTimeout))
            .tap { _ =>
              println("Resetting state to HalfOpen")
              halfOpenSwitch.set(true) <* state.set(HalfOpen) *> onStateChange(HalfOpen)
            }
            .runDrain
            .forkManaged
    } yield new Service {
      override def call[R, E, A](f: ZIO[R, E, A]): ZIO[R, CircuitBreakerCallError[E], A] =
        for {
          currentState <- state.get
//          _            = println(s"Current state ${currentState}")
          // TODO reset to HalfOpen state
          result <- currentState match {
                     case Open =>
                       ZIO.fail(CircuitBreakerOpen)
                     case HalfOpen =>
                       for {
                         letCallsThrough <- halfOpenSwitch.getAndUpdate(_ => false)
                         result <- if (letCallsThrough) {
                                    //noinspection SimplifyTapInspection
                                    f.mapError(WrappedError(_))
                                      .tapError { _ =>
                                        state.set(Open) *> resetRequests.offer(()) <* onStateChange(Open)
                                      }
                                      .tap { _ =>
                                        state.set(Closed) *> nrFailedCalls.set(0) <* onStateChange(Closed)
                                      }

                                  } else {
                                    ZIO.fail(CircuitBreakerOpen)
                                  }
                       } yield result

                     case Closed =>
                       // TODO the state may have changed already after completion of `f`
                       //noinspection SimplifyTapInspection
                       f.mapError(WrappedError(_))
                         .tapError { _ =>
                           for {
                             currentNrFailedCalls <- nrFailedCalls.updateAndGet(_ + 1)
                             _                    = println(s"Nr failed calls is ${currentNrFailedCalls}")
                             _ <- (state.set(Open) *> resetRequests.offer(()) <* onStateChange(Open))
                                   .when(currentNrFailedCalls >= maxFailures)
                           } yield ()
                         }
                         .tap(_ => nrFailedCalls.set(0))
                   }
        } yield result
    }

  sealed trait State
  case object Closed   extends State
  case object HalfOpen extends State
  case object Open     extends State

}
