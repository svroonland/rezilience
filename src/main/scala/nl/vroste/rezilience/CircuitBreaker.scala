package nl.vroste.rezilience
import nl.vroste.rezilience.CircuitBreaker.{ CircuitBreakerCallError, WrappedError }
import zio.clock.Clock
import zio.stream.ZStream
import zio._
import zio.duration._

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
 * - Open: all calls fail fast with a `CircuitBreakerOpen` error. After the reset timeout,
 *   the states changes to HalfOpen
 *
 * - HalfOpen: the first call is let through. Meanwhile all other calls fail with a
 *   `CircuitBreakerOpen` error. If the first call succeeds, the state changes to
 *   Closed again (normal operation). If it fails, the state changes back to Open.
 *   The reset timeout is governed by a reset policy, which is typically an exponential backoff.
 *
 * Notes:
 * - The maximum number of failures before tripping the circuit breaker is not absolute under
 *   concurrent execution. I.e. if you make 20 calls to a failing system in parallel via a circuit breaker
 *   with max 10 failures, the calls will be running concurrently. The circuit breaker will trip
 *   after 10 calls, but the remaining 10 that are in-flight will continue to run and fail as well.
 *
 *   TODO what to do if you want this kind of behavior, or should we make it an option?
 */
trait CircuitBreaker {

  /**
   * Execute a given effect with the circuit breaker
   *
   * @param f Effect to execute
   * @return A ZIO that either succeeds with the success of the given f or fails with either a `CircuitBreakerOpen`
   *         or a `WrappedError` of the error of the given f
   */
  def withCircuitBreaker[R, E, A](f: ZIO[R, E, A]): ZIO[R with Clock, CircuitBreakerCallError[E], A]

  /**
   * Execute the given effect with the circuit breaker
   *
   * Only failures that match according to `isFailure` are treated as failures by the circuit breaker. Other failures
   * are passed on, circumventing the circuit breaker's failure counter.
   *
   * @param f
   * @param isFailure
   * @tparam R
   * @tparam E
   * @tparam A
   * @return
   */
  def withCircuitBreaker[R, E, A](
    f: ZIO[R, E, A],
    isFailure: PartialFunction[E, Any]
  ): ZIO[R with Clock, CircuitBreakerCallError[E], A] =
    withCircuitBreaker {
      f.either.flatMap {
        case Left(e) if isFailure.isDefinedAt(e) => ZIO.fail(e)
        case Left(e)                             => ZIO.succeed(Left(WrappedError(e)))
        case Right(e)                            => ZIO.succeed(Right(e))
      }
    }.absolve

}

object CircuitBreaker {
  sealed trait CircuitBreakerCallError[+E]
  case object CircuitBreakerOpen       extends CircuitBreakerCallError[Nothing]
  case class WrappedError[E](error: E) extends CircuitBreakerCallError[E]

  import State._

  /**
   * Create a CircuitBreaker that resets according to the given reset policy
   *
   * @param maxFailures Maximum number of failures before tripping the circuit breaker
   * @param resetPolicy Reset schedule after too many failures. Typically an exponential backoff strategy is used.
   * @param onStateChange Observer for circuit breaker state changes
   * @return The CircuitBreaker as a managed resource
   */
  def make(
    maxFailures: Int,
    resetPolicy: Schedule[Clock, Any, Duration] = Schedule.exponential(1.second, 2.0),
    onStateChange: State => UIO[Unit] = _ => ZIO.unit
  ): ZManaged[Clock, Nothing, CircuitBreaker] =
    Ref.make[Int](0).toManaged_.flatMap { nrFailedCalls =>
      def shouldTrip(currentState: State) = nrFailedCalls.updateAndGet(_ + 1) map { failedCalls =>
        // The state may have already changed to Open or even HalfOpen.
        // This can happen if we fire X calls in parallel where X >= 2 * maxFailures
        failedCalls == maxFailures && currentState == Closed
      }
      def onReset = nrFailedCalls.set(0)

      makeGenericCircuitBreaker(shouldTrip, onReset, resetPolicy, onStateChange)
    }

  private def makeGenericCircuitBreaker(
    shouldTrip: State => UIO[Boolean],
    onReset: UIO[Unit],
    resetPolicy: Schedule[Clock, Any, Duration],
    onStateChange: State => UIO[Unit]
  ): ZManaged[Clock, Nothing, CircuitBreaker] =
    for {
      state          <- Ref.make[State](Closed).toManaged_
      halfOpenSwitch <- Ref.make[Boolean](true).toManaged_
      scheduleState  <- (resetPolicy.initial >>= (Ref.make[resetPolicy.State](_))).toManaged_
      resetRequests  <- ZQueue.bounded[Unit](1).toManaged_
      _ <- ZStream
            .fromQueue(resetRequests)
            .mapM { _ =>
              for {
                s        <- scheduleState.get
                newState <- resetPolicy.update((), s)
                _        <- scheduleState.set(newState)
                _        <- halfOpenSwitch.set(true)
                _        <- state.set(HalfOpen)
                _        <- onStateChange(HalfOpen)
              } yield ()
            }
            .runDrain
            .forkManaged
    } yield new CircuitBreaker {
      val changeToOpen = state.set(Open) *>
        resetRequests.offer(()) <*
        onStateChange(Open)

      val changeToClosed = onReset *>
        (resetPolicy.initial >>= scheduleState.set) *> // Reset the reset schedule
        state.set(Closed) <*
        onStateChange(Closed)

      override def withCircuitBreaker[R, E, A](f: ZIO[R, E, A]): ZIO[R with Clock, CircuitBreakerCallError[E], A] =
        for {
          currentState <- state.get
          result <- currentState match {
                     case Closed =>
                       val onSuccess       = onReset
                       def onFailure(e: E) = ZIO.whenM(state.get >>= shouldTrip)(changeToOpen)

                       f.tapBoth(onFailure, _ => onSuccess)
                         .mapError(WrappedError(_))
                     case Open =>
                       ZIO.fail(CircuitBreakerOpen)
                     case HalfOpen =>
                       for {
                         isFirstCall <- halfOpenSwitch.getAndUpdate(_ => false)
                         result <- if (isFirstCall) {
                                    val onFailure = changeToOpen
                                    val onSuccess = changeToClosed

                                    f.mapError(WrappedError(_)).tapBoth(_ => onFailure, _ => onSuccess)
                                  } else {
                                    ZIO.fail(CircuitBreakerOpen)
                                  }
                       } yield result
                   }
        } yield result
    }

  sealed trait State

  object State {
    case object Closed   extends State
    case object HalfOpen extends State
    case object Open     extends State
  }

}
