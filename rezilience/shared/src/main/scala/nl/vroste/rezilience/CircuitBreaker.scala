package nl.vroste.rezilience

import nl.vroste.rezilience.CircuitBreaker.{ CircuitBreakerCallError, State, StateChange }
import nl.vroste.rezilience.Policy.PolicyError
import zio._
import zio.metrics.{ Metric, MetricLabel }
import zio.stream.ZStream

import java.time.Instant

/**
 * CircuitBreaker protects external resources against overload under failure
 *
 * Operates in three states:
 *
 *   - Closed (initial state / normal operation): calls are let through normally. Call failures and successes update the
 *     call statistics, eg failure count. When the statistics satisfy some criteria, the circuit breaker is 'tripped'
 *     and set to the Open state. Note that after this switch, in-flight calls are not canceled. Their success or
 *     failure does not affect the circuit breaker anymore though.
 *   - Open: all calls fail fast with a `CircuitBreakerOpen` error. After the reset timeout, the states changes to
 *     HalfOpen
 *   - HalfOpen: the first call is let through. Meanwhile all other calls fail with a `CircuitBreakerOpen` error. If the
 *     first call succeeds, the state changes to Closed again (normal operation). If it fails, the state changes back to
 *     Open. The reset timeout is governed by a reset policy, which is typically an exponential backoff.
 *
 * Two tripping strategies are implemented: 1) Failure counting. When the number of successive failures exceeds a
 * threshold, the circuit breaker is tripped.
 *
 * Note that the maximum number of failures before tripping the circuit breaker is not absolute under concurrent
 * execution. I.e. if you make 20 calls to a failing system in parallel via a circuit breaker with max 10 failures, the
 * calls will be running concurrently. The circuit breaker will trip after 10 calls, but the remaining 10 that are
 * in-flight will continue to run and fail as well.
 *
 * TODO what to do if you want this kind of behavior, or should we make it an option?
 *
 * 2) Failure rate. When the fraction of failed calls in some sample period exceeds a threshold (between 0 and 1), the
 * circuit breaker is tripped. The decision to trip the Circuit Breaker is made after every call (including successful
 * ones!)
 *
 * CircuitBreaker can record the following metrics, if a non-empty set of MetricLabels is given:
 *   - rezilience_circuit_breaker_state: current state (0 = closed, 1 = half-open, 2 = open)
 *   - rezilience_circuit_breaker_state_changes: number of state changes
 *   - rezilience_circuit_breaker_calls_success: number of successful calls
 *   - rezilience_circuit_breaker_calls_failure: number of failed calls
 *   - rezilience_circuit_breaker_calls_rejected: number of calls rejected in the open state
 */
trait CircuitBreaker[-E] {
  self =>

  /**
   * Execute a given effect with the circuit breaker
   *
   * @param f
   *   Effect to execute
   * @return
   *   A ZIO that either succeeds with the success of the given f or fails with either a `CircuitBreakerOpen` or a
   *   `WrappedError` of the error of the given f
   */
  def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, CircuitBreakerCallError[E1], A]

  def toPolicy: Policy[E] = new Policy[E] {
    override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, PolicyError[E1], A] =
      self(f).mapError(_.fold(Policy.CircuitBreakerOpen, Policy.WrappedError(_)))
  }

  /**
   * Transform this policy to apply to larger class of errors
   *
   * Only for errors where the partial function is defined will errors be considered as failures, otherwise the error is
   * passed through to the caller
   *
   * @param pf
   *   Map an error of type E2 to an error of type E
   * @tparam E2
   * @return
   *   A new CircuitBreaker defined for failures of type E2
   */
  def widen[E2](pf: PartialFunction[E2, E]): CircuitBreaker[E2]

  /**
   * Stream of Circuit Breaker state changes
   *
   * Is backed by a zio.Hub, so each use of the Dequeue will receive all state changes
   */
  val stateChanges: ZIO[Scope, Nothing, Dequeue[StateChange]]

  def currentState: UIO[State]
}

object CircuitBreaker {

  import State._

  case class StateChange(from: State, to: State, at: Instant)

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
   * @param maxFailures
   *   Maximum number of failures before tripping the circuit breaker
   * @param resetPolicy
   *   Reset schedule after too many failures. Typically an exponential backoff strategy is used.
   * @param isFailure
   *   Only failures that match according to `isFailure` are treated as failures by the circuit breaker. Other failures
   *   are passed on, circumventing the circuit breaker's failure counter.
   * @param metricLabels
   *   Set of labels to annotate metrics with, to distinguish this circuit breaker from others in the same application.
   *   No metrics are recorded if None is passed.
   * @return
   *   The CircuitBreaker as a managed resource
   */
  def withMaxFailures[E](
    maxFailures: Int,
    resetPolicy: Schedule[Any, Any, Any] = Retry.Schedules.exponentialBackoff(1.second, 1.minute),
    isFailure: PartialFunction[E, Boolean] = isFailureAny[E],
    metricLabels: Option[Set[MetricLabel]] = None
  ): ZIO[Scope, Nothing, CircuitBreaker[E]] =
    make(TrippingStrategy.failureCount(maxFailures), resetPolicy, isFailure, metricLabels)

  /**
   * Create a CircuitBreaker with the given tripping strategy
   *
   * @param trippingStrategy
   *   Determines under which conditions the CircuitBraker trips
   * @param resetPolicy
   *   Reset schedule after too many failures. Typically an exponential backoff strategy is used.
   * @param isFailure
   *   Only failures that match according to `isFailure` are treated as failures by the circuit breaker. Other failures
   *   are passed on, circumventing the circuit breaker's failure counter.
   * @param metricLabels
   *   Set of labels to annotate metrics with, to distinguish this circuit breaker from others in the same application.
   *   No metrics are recorded if None is passed.
   * @return
   */
  def make[E](
    trippingStrategy: ZIO[Scope, Nothing, TrippingStrategy],
    resetPolicy: Schedule[Any, Any, Any] =
      Retry.Schedules.exponentialBackoff(1.second, 1.minute), // TODO should move to its own namespace
    isFailure: PartialFunction[E, Boolean] = isFailureAny[E],
    metricLabels: Option[Set[MetricLabel]] = None
  ): ZIO[Scope, Nothing, CircuitBreaker[E]] =
    for {
      strategy        <- trippingStrategy
      state           <- Ref.make[State](Closed)
      halfOpenSwitch  <- Ref.make[Boolean](true)
      schedule        <- resetPolicy.driver
      resetRequests   <- Queue.bounded[Unit](1)
      stateChangesHub <- Hub.sliding[StateChange](32).withFinalizer(_.shutdown)
      cb               = new CircuitBreakerImpl[resetPolicy.State, E](
                           state,
                           resetRequests,
                           strategy,
                           stateChangesHub,
                           schedule,
                           isFailure,
                           halfOpenSwitch,
                           metricLabels
                         )
      _               <- cb.resetProcess
      _               <- cb.trackStateChanges
    } yield cb

  private case class CircuitBreakerImpl[ScheduleState, -E](
    state: Ref[State],
    resetRequests: Queue[Unit],
    strategy: TrippingStrategy,
    stateChangesHub: Hub[StateChange],
    schedule: Schedule.Driver[ScheduleState, Any, Any, Any],
    isFailure: PartialFunction[E, Boolean],
    halfOpenSwitch: Ref[Boolean],
    labels: Option[Set[MetricLabel]]
  ) extends CircuitBreaker[E] {

    override val stateChanges: ZIO[Scope, Nothing, Dequeue[StateChange]] = stateChangesHub.subscribe

    val metrics = labels.map { labels =>
      CircuitBreakerMetrics(
        state = Metric
          .gauge("rezilience_circuit_breaker_state")
          .tagged(labels),
        nrStateChanges = Metric.counter("rezilience_circuit_breaker_state_changes").tagged(labels),
        callsSuccess = Metric.counter("rezilience_circuit_breaker_calls_success").tagged(labels),
        callsFailure = Metric.counter("rezilience_circuit_breaker_calls_failure").tagged(labels),
        callsRejected = Metric
          .counter("rezilience_circuit_breaker_calls_rejected")
          .tagged(labels)
      )
    }

    private def withMetrics(f: CircuitBreakerMetrics => UIO[Unit]): UIO[Unit] =
      ZIO.fromOption(metrics).flatMap(f).ignore

    val changeToOpen: ZIO[Any, Nothing, Unit] =
      for {
        oldState <- state.getAndSet(Open)
        _        <- resetRequests.offer(())
        now      <- ZIO.clockWith(_.instant)
        _        <- stateChangesHub.publish(StateChange(oldState, Open, now))
      } yield ()

    val changeToClosed: ZIO[Any, Nothing, Unit] = for {
      _        <- strategy.onReset
      _        <- schedule.reset
      now      <- ZIO.clockWith(_.instant)
      oldState <- state.getAndSet(Closed)
      _        <- stateChangesHub.publish(StateChange(oldState, Closed, now))
    } yield ()

    val resetProcess: ZIO[Scope, Nothing, Any] = ZStream
      .fromQueue(resetRequests)
      .mapZIO { _ =>
        for {
          _   <- schedule.next(()) // TODO handle schedule completion?
          _   <- halfOpenSwitch.set(true)
          _   <- state.set(HalfOpen)
          now <- ZIO.clockWith(_.instant)
          _   <- stateChangesHub.publish(StateChange(Open, HalfOpen, now))
        } yield ()
      }
      .runDrain
      .forkScoped

    val trackStateChanges: ZIO[Scope, Nothing, Any] = for {
      stateChanges <- stateChanges
      _            <-
        ZStream
          .fromQueue(stateChanges)
          .tap { stateChange =>
            val stateAsInt = stateChange.to match {
              case State.Closed   => 0
              case State.HalfOpen => 1
              case State.Open     => 2
            }

            withMetrics(metrics => metrics.nrStateChanges.increment *> metrics.state.set(stateAsInt.doubleValue))
          }
          .runDrain
          .forkScoped
    } yield ()

    override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, CircuitBreakerCallError[E1], A] =
      (for {
        currentState <- state.get
        result       <- currentState match {
                          case Closed =>
                            // The state may have already changed to Open or even HalfOpen.
                            // This can happen if we fire X calls in parallel where X >= 2 * maxFailures
                            def onComplete(callSuccessful: Boolean) =
                              (for {
                                shouldTrip   <- strategy.shouldTrip(callSuccessful)
                                currentState <- state.get
                                _            <- changeToOpen.when(currentState == Closed && shouldTrip)
                              } yield ()).uninterruptible

                            tapZIOOnUserDefinedFailure(f)(
                              onFailure = onComplete(callSuccessful = false),
                              onSuccess = onComplete(callSuccessful = true)
                            ).mapError(WrappedError(_))

                          case Open     =>
                            ZIO.fail(CircuitBreakerOpen)
                          case HalfOpen =>
                            for {
                              isFirstCall <- halfOpenSwitch.getAndUpdate(_ => false)
                              result      <- if (isFirstCall) {
                                               tapZIOOnUserDefinedFailure(f)(
                                                 onFailure = (strategy.shouldTrip(false) *> changeToOpen).uninterruptible,
                                                 onSuccess = (changeToClosed *> strategy.onReset).uninterruptible
                                               ).tapDefect(_ => (strategy.shouldTrip(false) *> changeToOpen).uninterruptible)
                                                 .mapError(WrappedError(_))
                                             } else {
                                               ZIO.fail(CircuitBreakerOpen)
                                             }
                            } yield result
                        }
      } yield result)
        .tapBoth(
          {
            case CircuitBreaker.CircuitBreakerOpen => withMetrics(_.callsRejected.increment)
            case CircuitBreaker.WrappedError(_)    => withMetrics(_.callsFailure.increment)
          },
          _ => withMetrics(_.callsSuccess.increment)
        )

    private def tapZIOOnUserDefinedFailure[R, E1 <: E, A](
      f: ZIO[R, E1, A]
    )(onFailure: ZIO[R, E1, Any], onSuccess: ZIO[R, E1, Any]): ZIO[R, E1, A] =
      f.tapBoth(
        {
          case e if isFailure.applyOrElse[E1, Boolean](e, _ => false) =>
            onFailure
          case _                                                      =>
            onSuccess
        },
        _ => onSuccess
      )

    def widen[E2](pf: PartialFunction[E2, E]): CircuitBreaker[E2] = CircuitBreakerImpl[ScheduleState, E2](
      state,
      resetRequests,
      strategy,
      stateChangesHub,
      schedule,
      pf andThen isFailure,
      halfOpenSwitch,
      labels
    )

    override def currentState: UIO[State] = state.get
  }

  private[rezilience] def isFailureAny[E]: PartialFunction[E, Boolean] = { case _ => true }

  private[rezilience] case class CircuitBreakerMetrics(
    state: Metric.Gauge[Double],
    nrStateChanges: Metric.Counter[Long],
    callsSuccess: Metric.Counter[Long],
    callsFailure: Metric.Counter[Long],
    callsRejected: Metric.Counter[Long]
  )
}
