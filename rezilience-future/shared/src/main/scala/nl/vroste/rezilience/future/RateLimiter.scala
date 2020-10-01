package nl.vroste.rezilience.future

import nl.vroste.rezilience.{ RateLimiter => ZioRateLimiter }

import scala.concurrent.{ ExecutionContext, Future }
import zio._
import zio.clock.Clock
import zio.internal.Platform

import scala.concurrent.duration.{ Duration, DurationInt }

/**
 * Limit the rate of execution of Futures
 */
class RateLimiter private (runtime: zio.Runtime.Managed[Any], inner: ZioRateLimiter) {

  /**
   * Run the given Future under rate limiting
   *
   * @param f A Future that will be started.
   *          Note that the Future is a by-name parameter to prevent eager execution of the Future
   * @tparam T Return type
   * @return Future that completes with the result of `f`
   */
  def apply[T](f: => Future[T]): CancelableFuture[T] =
    runtime.unsafeRunToFuture(inner(Task.fromFunctionFuture(_ => f)))

  /**
   * Shutdown the RateLimiter
   */
  def close(): Unit = runtime.shutdown()
}

object RateLimiter {

  /**
   * Creates a RateLimiter
   *
   * @param max Maximum number of calls in each interval
   * @param interval Interval duration
   * @param ec Execution context to run the rate limiter and Futures on
   * @return RateLimiter
   */
  def make(max: Long, interval: Duration = 1.second)(implicit ec: ExecutionContext): RateLimiter = {
    val inner = nl.vroste.rezilience.RateLimiter.make(max, zio.duration.Duration.fromScala(interval))

    val layer   = Clock.live >>> inner.toLayer
    val runtime = zio.Runtime.unsafeFromLayer(layer, Platform.fromExecutionContext(ec))

    new RateLimiter(runtime, runtime.unsafeRun(ZIO.service[ZioRateLimiter]))
  }
}
