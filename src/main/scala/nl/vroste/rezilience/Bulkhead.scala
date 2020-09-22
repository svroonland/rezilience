package nl.vroste.rezilience

import zio._
import zio.stream.{ ZSink, ZStream }
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
trait Bulkhead { self =>

  /**
   * Call the system protected by the Bulkhead
   *
   * @param task Task to execute. When the maximum number of in-flight calls is exceeded, the
   *             call will be queued.
   *
   * @return Effect that succeeds with the success of the given task or fails, when executed,
   *         with a WrappedError of the task's error, or when not executed, with a BulkheadRejection.
   */
  def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, BulkheadError[E], A]

  def toPolicy[E]: Policy[E, BulkheadError[E]] = new Policy[E, BulkheadError[E]] {
    override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, BulkheadError[E1], A] = self(f)
  }

  /**
   * Provides the number of in-flight and queued calls
   */
  def metrics: UIO[Metrics]
}

object Bulkhead {
  sealed trait BulkheadError[+E]
  final case class WrappedError[E](e: E) extends BulkheadError[E]
  final case object BulkheadRejection    extends BulkheadError[Nothing]

  final case class Metrics(inFlight: Int, inQueue: Int)

  private final case class State(enqueued: Int, inFlight: Int) {
    val total               = enqueued + inFlight
    def enqueue: State      = copy(enqueued = enqueued + 1)
    def startProcess: State = copy(enqueued = enqueued - 1, inFlight + 1)
    def endProcess: State   = copy(inFlight = inFlight - 1)

    override def toString = s"{enqueued=${enqueued},inFlight=${inFlight}}"
  }

  /**
   * Create a Bulkhead with the given parameters
   *
   * @param maxInFlightCalls Maxmimum of concurrent executing calls
   * @param maxQueueing Maximum queueing calls
   * @return
   */
  def make(maxInFlightCalls: Int, maxQueueing: Int = 32): ZManaged[Any, Nothing, Bulkhead] =
    for {
      // Create a queue with an upper bound, but the actual max queue size enforcing and dropping is done below
      queue             <-
        ZQueue.bounded[(UIO[Unit], Promise[BulkheadRejection.type, Unit])](maxInFlightCalls + maxQueueing).toManaged_
      inFlightAndQueued <- Ref.make(State(0, 0)).toManaged_
      _                 <- ZStream
                             .fromQueue(queue)
                             .mapConcatM { case (action, enqueued) =>
                               inFlightAndQueued.get.flatMap { state =>
                                 if (state.total < maxInFlightCalls + maxQueueing)
                                   (inFlightAndQueued.update(_.enqueue) *> enqueued.succeed(())).as(List(action))
                                 else
                                   enqueued.fail(BulkheadRejection).as(List.empty)
                               }
                             }
                             .buffer(maxQueueing)
                             .mapMPar(maxInFlightCalls) { task =>
                               inFlightAndQueued
                                 .update(_.startProcess)
                                 .bracket_(inFlightAndQueued.update(_.endProcess), task)
                             }
                             .runDrain
                             .fork
                             .toManaged_
    } yield new Bulkhead {
      override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, BulkheadError[E], A] =
        for {
          result     <- Promise.make[E, A]
          enqueued   <- Promise.make[BulkheadRejection.type, Unit]
          r          <- ZIO.environment[R]
          action      = task.provide(r).foldM(result.fail, result.succeed).unit
          isEnqueued <- queue.offer((action, enqueued))
          _          <- ZIO.fail(BulkheadRejection).when(!isEnqueued)
          _          <- enqueued.await
          r          <- result.await.mapError(WrappedError(_))
        } yield r

      override def metrics: UIO[Metrics] = (inFlightAndQueued.get.map(state => Metrics(state.inFlight, state.enqueued)))
    }
}
