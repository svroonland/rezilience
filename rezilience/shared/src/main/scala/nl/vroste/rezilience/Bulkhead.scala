package nl.vroste.rezilience

import nl.vroste.rezilience.Bulkhead._
import zio._
import zio.stream.ZStream

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

  def toPolicy: Policy[Any] = new Policy[Any] {
    override def apply[R, E1 <: Any, A](f: ZIO[R, E1, A]): ZIO[R, Policy.PolicyError[E1], A] =
      self(f).mapError(_.fold(Policy.BulkheadRejection, Policy.WrappedError(_)))
  }

  /**
   * Provides the number of in-flight and queued calls
   */
  def metrics: UIO[Metrics]
}

object Bulkhead {
  sealed trait BulkheadError[+E] { self =>
    final def toException: Exception = BulkheadException(self)

    final def fold[O](bulkheadRejection: O, unwrap: E => O): O = self match {
      case BulkheadRejection   => bulkheadRejection
      case WrappedError(error) => unwrap(error)
    }
  }

  final case class WrappedError[E](e: E) extends BulkheadError[E]
  case object BulkheadRejection          extends BulkheadError[Nothing]

  final case class Metrics(inFlight: Int, inQueue: Int)

  case class BulkheadException[E](error: BulkheadError[E]) extends Exception("Bulkhead error")

  private final case class State(enqueued: Int, inFlight: Int) {
    val total               = enqueued + inFlight
    def enqueue: State      = copy(enqueued + 1)
    def startProcess: State = copy(enqueued - 1, inFlight + 1)
    def endProcess: State   = copy(enqueued, inFlight - 1)

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
        ZQueue
          .bounded[UIO[Unit]](maxQueueing)
          .toManaged_
      inFlightAndQueued <- Ref.make(State(0, 0)).toManaged_
      onStart            = inFlightAndQueued.update(_.startProcess)
      onEnd              = inFlightAndQueued.update(_.endProcess)
      _                 <- ZStream
                             .fromQueue(queue)
                             .mapMPar(maxInFlightCalls) { task =>
                               onStart.bracket_(onEnd, task)
                             }
                             .runDrain
                             .fork
                             .toManaged_
    } yield new Bulkhead {
      override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, BulkheadError[E], A] =
        for {
          // Atomically enqueue if there's still enough room, otherwise fail with BulkheadRejection
          _           <- inFlightAndQueued.modify { state =>
                           if (state.total < maxInFlightCalls + maxQueueing)
                             (ZIO.unit, state.enqueue)
                           else
                             (ZIO.fail(BulkheadRejection), state)
                         }.flatten
          result      <- Promise.make[E, A]
          interrupted <- Promise.make[Nothing, Unit]
          env         <- ZIO.environment[R]
          action       = task.provide(env).foldM(result.fail, result.succeed).unit raceFirst interrupted.await
          resultValue <- (for {
                           _ <- queue.offer(action)
                           r <- result.await.mapError(WrappedError(_))
                         } yield r).onInterrupt(interrupted.succeed(()))
        } yield resultValue

      override def metrics: UIO[Metrics] = (inFlightAndQueued.get.map(state => Metrics(state.inFlight, state.enqueued)))
    }
}
