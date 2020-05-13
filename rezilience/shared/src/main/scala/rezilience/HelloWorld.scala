package rezilience

import zio.clock.Clock
import zio.{ App, Has, Ref, Task, ZIO, ZLayer, ZQueue }
import zio.console._
import zio.duration.Duration
import zio.stream.{ ZSink, ZStream }

object CircuitBreaker {
  val myCall: String => Task[Unit] = _ => Task.succeed(())

  type CircuitBreaker[E] = Has[CircuitBreaker.Service[E]]

  // TODO layered errors
  // TODO listeners
  // TODO how strict on max failures when you fire 200 calls simultaneously?
  // TODO First open-closed, then half-open
  // TODO reset with exponential backoff
  object CircuitBreaker {
    trait Service[E] {
      def call[R, A](f: ZIO[R, E, A]): ZIO[R, E, A]
    }

    def make[E](
      maxFailures: Int,
      resetTimeout: Duration,
      circuitBreakerClosedError: E
    ): ZLayer[Clock, Nothing, CircuitBreaker[E]] =
      ZLayer.fromManaged {
        for {
          state         <- Ref.make[State](Closed).toManaged_
          nrFailedCalls <- Ref.make[Int](0).toManaged_
          resetRequests <- ZQueue.bounded[Unit](1).toManaged_
          _ <- ZStream
                .fromQueue(resetRequests)
                .mapM(_ => ZIO.unit.delay(resetTimeout))
                .tap(_ => state.set(Closed))
                .runDrain
                .toManaged_
        } yield new Service[E] {
          override def call[R, A](f: ZIO[R, E, A]): ZIO[R, E, A] =
            for {
              currentState <- state.get
              // TODO reset to HalfOpen state
              result <- if (currentState == Open) {
                         ZIO.fail(circuitBreakerClosedError)
                       } else {
                         // TODO the state may have changed already after completion of `f`
                         //noinspection SimplifyTapInspection
                         f.tapError { _ =>
                           for {
                             currentNrFailedCalls <- nrFailedCalls.updateAndGet(_ + 1)
                             _ <- state
                                   .set(Open)
                                   .when(currentNrFailedCalls >= maxFailures)
                           } yield ()
                         }.tap(_ => nrFailedCalls.set(0))
                       }
            } yield result
        }
      }

    sealed trait State
    case object Closed   extends State
    case object HalfOpen extends State
    case object Open     extends State
  }
}

object HelloWorld extends App {

  def run(args: List[String]) =
    myAppLogic.fold(_ => 1, _ => 0)

  val myAppLogic =
    for {
      _    <- putStrLn("Hello! What is your name?")
      name <- getStrLn
      _    <- putStrLn(s"Hello, $name, welcome to ZIO!")
    } yield ()
}
