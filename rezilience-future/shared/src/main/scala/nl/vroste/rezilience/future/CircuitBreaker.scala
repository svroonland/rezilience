package nl.vroste.rezilience.future

import nl.vroste.rezilience.CircuitBreaker.{ isFailureAny, CircuitBreakerException, CircuitBreakerOpen, State }
import nl.vroste.rezilience.{ TrippingStrategy, CircuitBreaker => ZioCircuitBreaker }
import zio._
import zio.clock.Clock
import zio.internal.Platform

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
   * Create a Bulkhead with the given parameters
   *
   * @param maxInFlightCalls Maximum of concurrent executing calls
   * @param maxQueueing Maximum queueing calls
   * @param ec Execution context to run the Bulkhead and Futures on
   * @return
   */
  def make(
    trippingStrategy: ZManaged[Clock, Nothing, TrippingStrategy],
    resetPolicy: Schedule[Clock, Any, Any],
    isFailure: PartialFunction[Throwable, Boolean] = isFailureAny[Throwable],
    onStateChange: State => Future[Any] = _ => Future.successful(()),
    maxInFlightCalls: Int,
    maxQueueing: Int = 32
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
