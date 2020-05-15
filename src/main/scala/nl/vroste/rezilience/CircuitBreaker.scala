package nl.vroste.rezilience
import izumi.reflect.Tags.Tag
import zio.{ Ref, Task, UIO, ZIO, ZLayer, ZManaged, ZQueue }
import zio.clock.Clock
import zio.duration.Duration
import zio.stream.ZStream

object CircuitBreaker {
  // TODO layered errors?
  // TODO listeners
  // TODO how strict on max failures when you fire 200 calls simultaneously?
  // TODO First open-closed, then half-open
  // TODO reset with exponential backoff
  trait Service[+E] {
    def call[R, E1 >: E, A](f: ZIO[R, E1, A]): ZIO[R, E1, A]
  }

  def make[E: Tag](
    maxFailures: Int,
    resetTimeout: Duration,
    circuitBreakerClosedError: E,
    onStateChange: State => UIO[Unit] = _ => ZIO.unit
  ): ZManaged[Clock, Nothing, Service[E]] =
    for {
      state         <- Ref.make[State](Closed).toManaged_
      nrFailedCalls <- Ref.make[Int](0).toManaged_
      resetRequests <- ZQueue.bounded[Unit](1).toManaged_
      _ <- ZStream
            .fromQueue(resetRequests)
            .tap(_ => ZIO(println(s"Got reset request, delaying with ${resetTimeout}")))
            .mapM(_ => ZIO.unit.delay(resetTimeout))
            .tap { _ =>
              println("Resetting state to closed")
              state.set(Closed) *> nrFailedCalls.set(0) <* onStateChange(Closed)
            }
            .runDrain
            .forkManaged
    } yield new Service[E] {
      override def call[R, E1 >: E, A](f: ZIO[R, E1, A]): ZIO[R, E1, A] =
        for {
          currentState <- state.get
//          _            = println(s"Current state ${currentState}")
          // TODO reset to HalfOpen state
          result <- if (currentState == Open) {
                     ZIO.fail(circuitBreakerClosedError)
                   } else {
                     // TODO the state may have changed already after completion of `f`
                     //noinspection SimplifyTapInspection
                     f.tapError { _ =>
                       for {
                         currentNrFailedCalls <- nrFailedCalls.updateAndGet(_ + 1)
                         _                    = println(s"Nr failed calls is ${currentNrFailedCalls}")
                         _ <- (state.set(Open) *> resetRequests.offer(()) <* onStateChange(Open))
                               .when(currentNrFailedCalls >= maxFailures)
                       } yield ()
                     }.tap(_ => nrFailedCalls.set(0))
                   }
        } yield result
    }

  sealed trait State
  case object Closed extends State
//  case object HalfOpen extends State
  case object Open extends State

}
