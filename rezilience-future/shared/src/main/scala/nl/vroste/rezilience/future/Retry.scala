package nl.vroste.rezilience.future

import nl.vroste.rezilience.Retry.Schedules
import nl.vroste.rezilience.future.Util._
import nl.vroste.rezilience.{ Retry => ZioRetry }
import zio._
import zio.clock.Clock
import zio.internal.Platform
import zio.random.Random

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

/**
 * Retry on exceptions
 */
class Retry private (runtime: zio.Runtime.Managed[Any], inner: ZioRetry[Throwable]) {

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

object Retry {

  /**
   * Create a Retry policy with a common retry schedule
   *
   * By default the first retry is done immediately. With transient / random failures this method gives the
   * highest chance of fast success.
   * After that Retry uses exponential backoff between some minimum and maximum duration. Jitter is added
   * to prevent spikes of retries.
   * An optional maximum number of retries ensures that retrying does not continue forever.
   *
   * @param min Minimum retry backoff delay
   * @param max Maximum backoff time. When this value is reached, subsequent intervals will be equal to this value.
   * @param factor Factor with which delays increase
   * @param retryImmediately Retry immediately after the first failure
   * @param maxRetries Maximum number of retries
   * @param ec Execution context to run the Futures on
   */
  def make(
    min: Duration = 1.second,
    max: Duration = 1.minute,
    factor: Double = 2.0,
    retryImmediately: Boolean = true,
    maxRetries: Option[Int] = Some(3),
    isFailure: PartialFunction[Throwable, Boolean] = { case _ => true }
  )(implicit ec: ExecutionContext): Retry =
    make(Schedules.whenCase(isFailure)(Schedules.common(min, max, factor, retryImmediately, maxRetries)))

  /**
   * Create a Retry policy with a custom schedule
   *
   * @param schedule ZIO schedule
   * @param ec Execution context to run the Futures on
   */
  def make[R](schedule: Schedule[Clock with Random, Throwable, Any])(implicit ec: ExecutionContext): Retry = {
    val inner = nl.vroste.rezilience.Retry.make(schedule)

    val layer   = (Clock.live ++ Random.live) >>> inner.toLayer
    val runtime = zio.Runtime.unsafeFromLayer(layer, Platform.fromExecutionContext(ec))

    new Retry(runtime, runtime.unsafeRun(ZIO.service[ZioRetry[Throwable]]))
  }
}
