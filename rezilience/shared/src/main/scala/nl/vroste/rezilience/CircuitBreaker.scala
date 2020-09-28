package nl.vroste.rezilience

import nl.vroste.rezilience.CircuitBreaker.CircuitBreakerCallError
import nl.vroste.rezilience.Policy.{ circuitBreakerErrorToPolicyError, PolicyError }
import zio._
import zio.clock.Clock
import zio.duration._
import zio.stream.ZStream

/**
 * CircuitBreaker protects external resources against overload under failure
 *
 * Operates in three states:
 *
 * - Closed (initial state / normal operation): calls are let through normally. Call failures and successes
 * update the call statistics, eg failure count. When the statistics satisfy some criteria, the circuit
 * breaker is 'tripped' and set to the Open state. Note that after this switch, in-flight calls are not canceled.
 * Their success  or failure does not affect the circuit breaker anymore though.
 *
 * - Open: all calls fail fast with a `CircuitBreakerOpen` error. After the reset timeout,
 * the states changes to HalfOpen
 *
 * - HalfOpen: the first call is let through. Meanwhile all other calls fail with a
 * `CircuitBreakerOpen` error. If the first call succeeds, the state changes to
 * Closed again (normal operation). If it fails, the state changes back to Open.
 * The reset timeout is governed by a reset policy, which is typically an exponential backoff.
 *
 * Two tripping strategies are implemented:
 * 1) Failure counting. When the number of successive failures exceeds a threshold, the circuit
 * breaker is tripped.
 *
 * Note that the maximum number of failures before tripping the circuit breaker is not absolute under
 * concurrent execution. I.e. if you make 20 calls to a failing system in parallel via a circuit breaker
 * with max 10 failures, the calls will be running concurrently. The circuit breaker will trip
 * after 10 calls, but the remaining 10 that are in-flight will continue to run and fail as well.
 *
 * TODO what to do if you want this kind of behavior, or should we make it an option?
 *
 * 2) Failure rate. When the fraction of failed calls in some sample period exceeds
 * a threshold (between 0 and 1), the circuit breaker is tripped.
 */
trait CircuitBreaker[-E] {
  self =>

  /**
   * Execute a given effect with the circuit breaker
   *
   * @param f Effect to execute
   * @return A ZIO that either succeeds with the success of the given f or fails with either a `CircuitBreakerOpen`
   *         or a `WrappedError` of the error of the given f
   */
  def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, CircuitBreakerCallError[E1], A]

  def toPolicy: Policy[E] = new Policy[E] {
    override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, PolicyError[E1], A] =
      self(f).mapError(circuitBreakerErrorToPolicyError)
  }

  /**
   * Transform this policy to apply to larger class of errors
   *
   * Only for errors where the partial function is defined will errors be considered as failures, otherwise
   * the error is passed through to the caller
   *
   * @param pf Map an error of type E2 to an error of type E
   * @tparam E2
   * @return A new CircuitBreaker defined for failures of type E2
   */
  def widen[E2](pf: PartialFunction[E2, E]): CircuitBreaker[E2]
}

object CircuitBreaker {

  import State._

  sealed trait CircuitBreakerCallError[+E] { self =>
    def toException: Exception = CircuitBreakerException(self)

    def fold[O](circuitBreakerOpen: O, unwrap: E => O): O = self match {
      case CircuitBreakerOpen  => circuitBreakerOpen
      case WrappedError(error) => unwrap(error)
    }
  }

  case object CircuitBreakerOpen       extends CircuitBreakerCallError[Nothing]
  case class WrappedError[E](error: E) extends CircuitBreakerCallError[E]

  case class CircuitBreakerException[E](error: CircuitBreakerCallError[E]) extends Exception("Circuit breaker error")

  sealed trait State

  object State {
    case object Closed   extends State
    case object HalfOpen extends State
    case object Open     extends State
  }

  /**
   * Create a CircuitBreaker that fails when a number of successive failures (no pun intended) has been counted
   *
   * @param maxFailures   Maximum number of failures before tripping the circuit breaker
   * @param resetPolicy   Reset schedule after too many failures. Typically an exponential backoff strategy is used.
   * @param isFailure     Only failures that match according to `isFailure` are treated as failures by the circuit breaker.
   *                      Other failures are passed on, circumventing the circuit breaker's failure counter.
   * @param onStateChange Observer for circuit breaker state changes
   * @return The CircuitBreaker as a managed resource
   */
  def withMaxFailures[E](
    maxFailures: Int,
    resetPolicy: Schedule[Clock, Any, Duration] = Schedule.exponential(1.second, 2.0),
    isFailure: PartialFunction[E, Boolean] = isFailureAny[E],
    onStateChange: State => UIO[Unit] = _ => ZIO.unit
  ): ZManaged[Clock, Nothing, CircuitBreaker[E]] =
    make(TrippingStrategy.failureCount(maxFailures), resetPolicy, isFailure, onStateChange)

  /**
   * Create a CircuitBreaker with the given tripping strategy
   *
   * @param trippingStrategy Determines under which conditions the CircuitBraker trips
   * @param resetPolicy      Reset schedule after too many failures. Typically an exponential backoff strategy is used.
   * @param isFailure        Only failures that match according to `isFailure` are treated as failures by the circuit breaker.
   *                         Other failures are passed on, circumventing the circuit breaker's failure counter.
   * @param onStateChange    Observer for circuit breaker state changes
   * @return
   */
  def make[E](
    trippingStrategy: ZManaged[Clock, Nothing, TrippingStrategy],
    resetPolicy: Schedule[Clock, Any, Any],
    isFailure: PartialFunction[E, Boolean] = isFailureAny[E],
    onStateChange: State => UIO[Unit] = _ => ZIO.unit
  ): ZManaged[Clock, Nothing, CircuitBreaker[E]] =
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
    } yield new CircuitBreakerImpl[E](
      state,
      resetRequests,
      strategy,
      onStateChange,
      schedule,
      isFailure,
      halfOpenSwitch
    )

  private case class CircuitBreakerImpl[-E](
    state: Ref[State],
    resetRequests: Queue[Unit],
    strategy: TrippingStrategy,
    onStateChange: State => UIO[Unit],
    schedule: Schedule.Driver[Clock, Any, Any],
    isFailure: PartialFunction[E, Boolean],
    halfOpenSwitch: Ref[Boolean]
  ) extends CircuitBreaker[E] {

    val changeToOpen = state.set(Open) *>
      resetRequests.offer(()) <*
      onStateChange(Open).fork // Do not wait for user code

    val changeToClosed = strategy.onReset *>
      schedule.reset *>
      state.set(Closed) <*
      onStateChange(Closed).fork // Do not wait for user code

    override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, CircuitBreakerCallError[E1], A] =
      for {
        currentState <- state.get
        result       <- currentState match {
                          case Closed   =>
                            // The state may have already changed to Open or even HalfOpen.
                            // This can happen if we fire X calls in parallel where X >= 2 * maxFailures
                            def onFail =
                              (strategy.onFailure *>
                                ZIO.whenM(state.get.flatMap(s => strategy.shouldTrip.map(_ && (s == Closed)))) {
                                  changeToOpen
                                }).uninterruptible

                            f.either.flatMap {
                              case Left(e) if isFailure.isDefinedAt(e) => ZIO.fail(e)
                              case Left(e)                             => ZIO.left(WrappedError(e))
                              case Right(e)                            => ZIO.right(e)

                            }
                              .tapBoth(_ => onFail, _ => strategy.onSuccess)
                              .mapError(WrappedError(_))
                              .absolve
                          case Open     =>
                            ZIO.fail(CircuitBreakerOpen)
                          case HalfOpen =>
                            for {
                              isFirstCall <- halfOpenSwitch.getAndUpdate(_ => false)
                              result      <- if (isFirstCall) {
                                               f.mapError(WrappedError(_))
                                                 .tapBoth(
                                                   _ => (strategy.onFailure *> changeToOpen).uninterruptible,
                                                   _ => (changeToClosed *> strategy.onReset).uninterruptible
                                                 )
                                             } else {
                                               ZIO.fail(CircuitBreakerOpen)
                                             }
                            } yield result
                        }
      } yield result

    def widen[E2](pf: PartialFunction[E2, E]): CircuitBreaker[E2] = CircuitBreakerImpl[E2](
      state,
      resetRequests,
      strategy,
      onStateChange,
      schedule,
      pf andThen isFailure,
      halfOpenSwitch
    )
  }

  private def isFailureAny[E]: PartialFunction[E, Boolean] = { case _ => true }
}
