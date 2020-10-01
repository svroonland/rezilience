package nl.vroste.rezilience.future

import nl.vroste.rezilience.CircuitBreaker.{ isFailureAny, CircuitBreakerException, CircuitBreakerOpen, State }
import nl.vroste.rezilience.{ Retry, TrippingStrategy, CircuitBreaker => ZioCircuitBreaker }
import Util._
import zio._
import zio.clock.Clock
import zio.internal.Platform

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

/**
 * Protect external resources against overload under failure
 */
class CircuitBreaker private (runtime: zio.Runtime.Managed[Any], inner: ZioCircuitBreaker[Throwable]) {

  /**
   * Run the given Future
   *
   * @param f A Future that will be started.
   *          Note that the Future is a by-name parameter to prevent eager execution of the Future
   * @tparam T Return type
   * @return Future that completes with the result of `f` or fails with a `CircuitBreakerException`
   */
  def apply[T](f: => Future[T]): CancelableFuture[T] =
    runtime.unsafeRunToFuture {
      inner(Task.fromFunctionFuture(_ => f))
        .mapError(_.fold(CircuitBreakerException(CircuitBreakerOpen), identity))
    }

  /**
   * Shutdown the CircuitBreaker
   */
  def close(): Unit = runtime.shutdown()
}

object CircuitBreaker {

  /**
   * Create a CircuitBreaker that trips after a number of subsequent failures
   *
   * Uses exponential backoff for re-closing the circuit breaker
   *
   * @param maxFailures Number of subsequent failures before opening the circuit breaker
   * @param backoffMin Minimum time to backoff for letting calls through again
   * @param backoffMax Maximum time to backoff for letting calls through again
   * @param isFailure Determine which exceptions the circuit breaker should act upon
   * @param onStateChange Callback for CircuitBreaker state changes
   * @param ec Execution context to run the CircuitBreaker and Futures on
   */
  def withMaxFailures(
    maxFailures: Int = 10,
    backoffMin: Duration = 1.second,
    backoffMax: Duration = 1.minute,
    isFailure: PartialFunction[Throwable, Boolean] = isFailureAny[Throwable],
    onStateChange: State => Future[Any] = _ => Future.successful(())
  )(implicit ec: ExecutionContext): CircuitBreaker =
    make(
      TrippingStrategy.failureCount(maxFailures),
      Retry.Schedules.exponentialBackoff(backoffMin, backoffMax),
      isFailure,
      onStateChange
    )

  /**
   * Create a CircuitBreaker that trips after a number of subsequent failures
   *
   * For a CircuitBreaker that fails when the fraction of failures in a sample period exceeds some threshold
   *
   * @param failureRateThreshold The minimum fraction (between 0.0 and 1.0) of calls that must fail within
   *                             the sample duration for the circuit breaker to trip
   * @param sampleDuration Minimum amount of time to record calls
   * @param minThroughput Minimum number of calls required to evaluate the actual failure rate.
   * @param backoffMin Minimum time to backoff for letting calls through again
   * @param backoffMax Maximum time to backoff for letting calls through again
   * @param isFailure Determine which exceptions the circuit breaker should act upon
   * @param onStateChange Callback for CircuitBreaker state changes
   * @param ec Execution context to run the CircuitBreaker and Futures on
   */
  def withFailureRate(
    failureRateThreshold: Double = 0.5,
    sampleDuration: Duration = 1.minute,
    minThroughput: Int = 10,
    nrSampleBuckets: Int = 10,
    backoffMin: Duration = 1.second,
    backoffMax: Duration = 1.minute,
    isFailure: PartialFunction[Throwable, Boolean] = isFailureAny[Throwable],
    onStateChange: State => Future[Any] = _ => Future.successful(())
  )(implicit ec: ExecutionContext): CircuitBreaker =
    make(
      TrippingStrategy.failureRate(failureRateThreshold, sampleDuration, minThroughput, nrSampleBuckets),
      Retry.Schedules.exponentialBackoff(backoffMin, backoffMax),
      isFailure,
      onStateChange
    )

  /**
   * Create a CircuitBreaker with the given tripping strategy
   *
   * Some parameters here are types from the ZIO library. They can be created without the need for a ZIO environment or such.
   *
   * @param trippingStrategy Determines under which conditions the CircuitBreaker trips
   * @param resetPolicy      Reset schedule after too many failures. Typically an exponential backoff strategy is used.
   * @param isFailure        Only failures that match according to `isFailure` are treated as failures by the circuit breaker.
   *                         Other failures are passed on, circumventing the circuit breaker's failure counter.
   * @param onStateChange    Observer for circuit breaker state changes
   */
  def make(
    trippingStrategy: ZManaged[Clock, Nothing, TrippingStrategy],
    resetPolicy: Schedule[Clock, Any, Any] = Schedule.exponential(1.second, 2.0),
    isFailure: PartialFunction[Throwable, Boolean] = isFailureAny[Throwable],
    onStateChange: State => Future[Any] = _ => Future.successful(())
  )(implicit ec: ExecutionContext): CircuitBreaker = {
    val inner = nl.vroste.rezilience.CircuitBreaker.make[Throwable](
      trippingStrategy,
      resetPolicy,
      isFailure,
      state => Task.fromFunctionFuture(_ => onStateChange(state)).orDie.ignore
    )

    val layer   = Clock.live >>> inner.toLayer
    val runtime = zio.Runtime.unsafeFromLayer(layer, Platform.fromExecutionContext(ec))

    new CircuitBreaker(runtime, runtime.unsafeRun(ZIO.service[ZioCircuitBreaker[Throwable]]))
  }
}
