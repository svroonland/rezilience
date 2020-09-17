package nl.vroste.rezilience

import zio._
import zio.stream.ZStream
import Bulkhead._

/**
 * Limits the number of simultaneous in-flight calls to an external resource
 *
 * A bulkhead limits the resources used by some system by limiting the number of concurrent calls to that system.
 * Calls that exceed that number are rejected with a `BulkheadError`. To ensure good utilisation of the system, however,
 * there is a queue/buffer of waiting calls.
 *
 * It also prevents queueing up of requests, which consume resources in the calling system, by rejecting
 * calls when the queue is full.
 */
trait Bulkhead {

  /**
   * Call the system protected by the Bulkhead
   *
   * @param task Task to execute. When the maximum number of in-flight calls is exceeded, the
   *             call will be queued.
   *
   * @return Effect that succeeds with the success of the given task or fails, when executed,
   *         with a WrappedError of the task's error, or when not executed, with a BulkheadRejection.
   */
  def call[R, E, A](task: ZIO[R, E, A]): ZIO[R, BulkheadError[E], A]

  /**
   * Provides the number of in-flight and queued calls
   */
  def metrics: UIO[Metrics]
}

object Bulkhead {
  sealed trait BulkheadError[+E]
  case class WrappedError[E](e: E) extends BulkheadError[E]
  case object BulkheadRejection    extends BulkheadError[Nothing]

  case class Metrics(inFlight: Int, inQueue: Int)

  /**
   * Create a Bulkhead with the given parameters
   *
   * @param maxInFlightCalls Maxmimum of concurrent executing calls
   * @param maxQueueing Maximum queueing calls
   * @return
   */
  def make(maxInFlightCalls: Int, maxQueueing: Int = 32): ZManaged[Any, Nothing, Bulkhead] =
    for {
      queue    <- ZQueue.dropping[UIO[Unit]](maxQueueing).toManaged_
      inFlight <- Ref.make[Int](0).toManaged_
      _        <- ZStream
                    .fromQueue(queue)
                    .mapMPar(maxInFlightCalls) { task =>
                      inFlight.update(_ + 1).bracket_(inFlight.update(_ - 1), task)
                    }
                    .runDrain
                    .fork
                    .toManaged_
    } yield new Bulkhead {
      override def call[R, E, A](task: ZIO[R, E, A]): ZIO[R, BulkheadError[E], A] =
        for {
          p          <- Promise.make[E, A]
          r          <- ZIO.environment[R]
          isEnqueued <- queue.offer(task.provide(r).foldM(p.fail, p.succeed).unit)
          _          <- ZIO.fail(BulkheadRejection).when(!isEnqueued)
          result     <- p.await.mapError(WrappedError(_))
        } yield result

      override def metrics: UIO[Metrics] = (inFlight.get zip queue.size).map(Function.tupled(Metrics.apply))
    }
}
