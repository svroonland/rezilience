package nl.vroste.rezilience

import nl.vroste.rezilience.CircuitBreaker.{ CircuitBreakerCallError, WrappedError }
import zio.clock.Clock
import zio.stream.ZStream
import zio._
import zio.duration._

/**
 * CircuitBreaker protects external resources against overload under failure
 *
 * Operates in three states:
 *
 * - Closed (initial state / normal operation): calls are let through normally. Call failures and successes
 *   update the call statistics, eg failure count. When the statistics satisfy some criteria, the circuit
 *   breaker is 'tripped' and set to the Open state. Note that after this switch, in-flight calls are not canceled.
 *   Their success  or failure does not affect the circuit breaker anymore though.
 *
 * - Open: all calls fail fast with a `CircuitBreakerOpen` error. After the reset timeout,
 *   the states changes to HalfOpen
 *
 * - HalfOpen: the first call is let through. Meanwhile all other calls fail with a
 *   `CircuitBreakerOpen` error. If the first call succeeds, the state changes to
 *   Closed again (normal operation). If it fails, the state changes back to Open.
 *   The reset timeout is governed by a reset policy, which is typically an exponential backoff.
 *
 * Two tripping strategies are implemented:
 * 1) Failure counting. When the number of successive failures exceeds a threshold, the circuit
 *    breaker is tripped.
 *
 *   Note that the maximum number of failures before tripping the circuit breaker is not absolute under
 *   concurrent execution. I.e. if you make 20 calls to a failing system in parallel via a circuit breaker
 *   with max 10 failures, the calls will be running concurrently. The circuit breaker will trip
 *   after 10 calls, but the remaining 10 that are in-flight will continue to run and fail as well.
 *
 *   TODO what to do if you want this kind of behavior, or should we make it an option?
 *
 * 2) Failure rate. When the fraction of failed calls in some sample period exceeds
 *    a threshold (between 0 and 1), the circuit breaker is tripped.
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
        case Left(e)                             => ZIO.left(WrappedError(e))
        case Right(e)                            => ZIO.right(e)
      }
    }.absolve
}

object CircuitBreaker {
  import State._

  sealed trait CircuitBreakerCallError[+E]
  case object CircuitBreakerOpen       extends CircuitBreakerCallError[Nothing]
  case class WrappedError[E](error: E) extends CircuitBreakerCallError[E]

  sealed trait State

  object State {
    case object Closed   extends State
    case object HalfOpen extends State
    case object Open     extends State
  }

  /**
   * Create a CircuitBreaker that fails when a number of successive failures (no pun intended) has been counted
   *
   * @param maxFailures Maximum number of failures before tripping the circuit breaker
   * @param resetPolicy Reset schedule after too many failures. Typically an exponential backoff strategy is used.
   * @param onStateChange Observer for circuit breaker state changes
   * @return The CircuitBreaker as a managed resource
   */
  def withMaxFailures(
    maxFailures: Int,
    resetPolicy: Schedule[Clock, Any, Duration] = Schedule.exponential(1.second, 2.0),
    onStateChange: State => UIO[Unit] = _ => ZIO.unit
  ): ZManaged[Clock, Nothing, CircuitBreaker] =
    make(TrippingStrategy.failureCount(maxFailures), resetPolicy, onStateChange)

  /**
   * Create a CircuitBreaker with the given tripping strategy
   *
   * @param trippingStrategy Determines under which conditions the CircuitBraker trips
   * @param resetPolicy Reset schedule after too many failures. Typically an exponential backoff strategy is used.
   * @param onStateChange Observer for circuit breaker state changes
   * @return
   */
  def make[R1](
    trippingStrategy: ZManaged[R1, Nothing, TrippingStrategy],
    resetPolicy: Schedule[Clock, Any, Any],
    onStateChange: State => UIO[Unit] = _ => ZIO.unit
  ): ZManaged[R1 with Clock, Nothing, CircuitBreaker] =
    for {
      strategy       <- trippingStrategy
      state          <- Ref.make[State](Closed).toManaged_
      halfOpenSwitch <- Ref.make[Boolean](true).toManaged_
      schedule       <- resetPolicy.driver.toManaged_
      resetRequests  <- ZQueue.bounded[Unit](1).toManaged_
      _              <- ZStream
                          .fromQueue(resetRequests)
                          .mapM { _ =>
                            for {
                              _ <- schedule.next(()) // TODO handle schedule completion?
                              _ <- halfOpenSwitch.set(true)
                              _ <- state.set(HalfOpen)
                              _ <- onStateChange(HalfOpen).fork // Do not wait for user code
                            } yield ()
                          }
                          .runDrain
                          .forkManaged
    } yield new CircuitBreaker {

      val changeToOpen = state.set(Open) *>
        resetRequests.offer(()) <*
        onStateChange(Open).fork // Do not wait for user code

      val changeToClosed = strategy.onReset *>
        schedule.reset *>
        state.set(Closed) <*
        onStateChange(Closed).fork // Do not wait for user code

      override def withCircuitBreaker[R, E, A](f: ZIO[R, E, A]): ZIO[R with Clock, CircuitBreakerCallError[E], A] =
        for {
          currentState <- state.get
          result       <- currentState match {
                            case Closed   =>
                              // The state may have already changed to Open or even HalfOpen.
                              // This can happen if we fire X calls in parallel where X >= 2 * maxFailures
                              def onFail =
                                strategy.onFailure *>
                                  ZIO.whenM(state.get.flatMap(s => strategy.shouldTrip.map(_ && (s == Closed)))) {
                                    changeToOpen
                                  }

                              f.tapBoth(_ => onFail, _ => strategy.onSuccess)
                                .mapError(WrappedError(_))
                            case Open     =>
                              ZIO.fail(CircuitBreakerOpen)
                            case HalfOpen =>
                              for {
                                isFirstCall <- halfOpenSwitch.getAndUpdate(_ => false)
                                result      <- if (isFirstCall) {
                                                 f.mapError(WrappedError(_))
                                                   .tapBoth(
                                                     _ => strategy.onFailure *> changeToOpen,
                                                     _ => changeToClosed *> strategy.onReset
                                                   )
                                               } else {
                                                 ZIO.fail(CircuitBreakerOpen)
                                               }
                              } yield result
                          }
        } yield result
    }

}
