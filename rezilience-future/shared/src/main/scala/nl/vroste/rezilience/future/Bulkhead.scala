package nl.vroste.rezilience.future

import nl.vroste.rezilience.Bulkhead.{ BulkheadException, BulkheadRejection }
import nl.vroste.rezilience.{ Bulkhead => ZioBulkhead }
import zio._
import zio.clock.Clock
import zio.internal.Platform

import scala.concurrent.{ ExecutionContext, Future }

/**
 * Limit the number of concurrent executions of Futures
 */
class Bulkhead private (runtime: zio.Runtime.Managed[Any], inner: ZioBulkhead) {

  /**
   * Run the given Future
   *
   * @param f A Future that will be started.
   *          Note that the Future is a by-name parameter to prevent eager execution of the Future
   * @tparam T Return type
   * @return Future that completes with the result of `f` or fails with a `BulkheadException`
   */
  def apply[T](f: => Future[T]): CancelableFuture[T] =
    runtime.unsafeRunToFuture {
      inner(Task.fromFunctionFuture(_ => f))
        .mapError(_.fold(BulkheadException(BulkheadRejection), identity))
    }

  /**
   * Shutdown the Bulkhead
   */
  def close(): Unit = runtime.shutdown()
}

object Bulkhead {

  /**
   * Create a Bulkhead with the given parameters
   *
   * @param maxInFlightCalls Maximum of concurrent executing calls
   * @param maxQueueing Maximum queueing calls
   * @param ec Execution context to run the Bulkhead and Futures on
   * @return
   */
  def make(maxInFlightCalls: Int, maxQueueing: Int = 32)(implicit ec: ExecutionContext): Bulkhead = {
    val inner = nl.vroste.rezilience.Bulkhead.make(maxInFlightCalls, maxQueueing)

    val layer   = Clock.live >>> inner.toLayer
    val runtime = zio.Runtime.unsafeFromLayer(layer, Platform.fromExecutionContext(ec))

    new Bulkhead(runtime, runtime.unsafeRun(ZIO.service[ZioBulkhead]))
  }
}
