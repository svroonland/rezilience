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
  // TODO custom definition of failure, i.e. not all E is failure to circuit breaker
  /**
   * Circuit Breaker protects external resources against overload under failure
   *
   * Operates in three states:
   *
   * - Closed (initial state / normal operation): calls are let through normally. Call failures increase
   *   a failure counter, call successes reset the failure counter to 0. When the
   *   failure count reaches the max, the circuit breaker is 'tripped' and set to the
   *   Open state. Note that after this switch, in-flight calls are not canceled. Their success
   *   or failure does not affect the circuit breaker anymore though.
   *
   * - Open: all calls fail fast with a [[CircuitBreakerOpen]] error. After the reset timeout,
   *   the states changes to HalfOpen
   *
   * - HalfOpen: the first call is let through. Meanwhile all other calls fail with a
   *   [[CircuitBreakerOpen]] error. If the first call succeeds, the state changes to
   *   Closed again (normal operation). If it fails, the state changes back to Open.
   *   The reset timeout is then increased exponentially.
   */
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
          result <- currentState match {
                     case Closed =>
                       // TODO the state may have changed already after completion of `f`
                       def onSuccess(x: A) = nrFailedCalls.set(0)
                       def onFailure(e: WrappedError[E]) =
                         nrFailedCalls.updateAndGet(_ + 1).flatMap { failedCalls =>
                           println(s"Nr failed calls is ${failedCalls}")

                           ZIO.when(failedCalls >= maxFailures)(
                             state.set(Open) *> resetRequests.offer(()) <* onStateChange(Open)
                           )
                         }

                       f.mapError(WrappedError(_))
                         .tapBoth(onFailure, onSuccess)
                     case Open =>
                       ZIO.fail(CircuitBreakerOpen)
                     case HalfOpen =>
                       for {
                         isFirstCall <- halfOpenSwitch.getAndUpdate(_ => false)
                         result <- if (isFirstCall) {
                                    def onFailure(e: WrappedError[E]) =
                                      state.set(Open) *> resetRequests.offer(()) <* onStateChange(Open)
                                    def onSuccess(x: A) =
                                      state.set(Closed) *> nrFailedCalls.set(0) <* onStateChange(Closed)

                                    f.mapError(WrappedError(_))
                                      .tapBoth(onFailure, onSuccess)

                                  } else {
                                    ZIO.fail(CircuitBreakerOpen)
                                  }
                       } yield result
                   }
        } yield result
    }

  sealed trait State
  case object Closed   extends State
  case object HalfOpen extends State
  case object Open     extends State

}
