package nl.vroste.rezilience

import nl.vroste.rezilience.Bulkhead._
import zio._
import zio.stream.ZStream

/**
 * Limits the number of simultaneous in-flight calls to an external resource
 *
 * A bulkhead limits the resources used by some system by limiting the number of concurrent calls to that system. Calls
 * that exceed that number are rejected with a `BulkheadError`. To ensure good utilisation of the system, however, there
 * is a queue/buffer of waiting calls.
 *
 * It also prevents queueing up of requests, which consume resources in the calling system, by rejecting calls when the
 * queue is full.
 */
trait Bulkhead { self =>

  /**
   * Call the system protected by the Bulkhead
   *
   * @param task
   *   Task to execute. When the maximum number of in-flight calls is exceeded, the call will be queued.
   *
   * @return
   *   Effect that succeeds with the success of the given task or fails, when executed, with a WrappedError of the
   *   task's error, or when not executed, with a BulkheadRejection.
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
   * @param maxInFlightCalls
   *   Maxmimum of concurrent executing calls
   * @param maxQueueing
   *   Maximum queueing calls
   * @return
   */
  def make(maxInFlightCalls: Int, maxQueueing: Int = 32): ZIO[Scope, Nothing, Bulkhead] =
    for {
      queue             <- Queue
                             .bounded[UIO[Unit]](zio.internal.RingBuffer.nextPow2(maxQueueing))
      inFlightAndQueued <- Ref.make(State(0, 0))
      onStart            = inFlightAndQueued.update(_.startProcess)
      onEnd              = inFlightAndQueued.update(_.endProcess)
      _                 <- ZStream
                             .fromQueueWithShutdown(queue)
                             .mapZIOPar(maxInFlightCalls) { task =>
                               onStart.acquireRelease(onEnd, task)
                             }
                             .runDrain
                             .forkScoped
    } yield new Bulkhead {
      override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, BulkheadError[E], A] =
        for {
          start                  <- Promise.make[Nothing, Unit]
          done                   <- Promise.make[Nothing, Unit]
          action                  = start.succeed(()) *> done.await
          // Atomically enqueue and update queue state if there's still enough room, otherwise fail with BulkheadRejection
          enqueueAction           =
            inFlightAndQueued.modify { state =>
              if (state.total < maxInFlightCalls + maxQueueing)
                (queue.offer(action), state.enqueue)
              else
                (ZIO.fail(BulkheadRejection), state)

            }.flatten.uninterruptible
          onInterruptOrCompletion = done.succeed(())
          result                 <- ZIO.scoped[R] {
                                      ZIO
                                        .acquireReleaseInterruptible(enqueueAction.onInterrupt(onInterruptOrCompletion))(
                                          onInterruptOrCompletion
                                        ) *> start.await *> task.mapError(WrappedError(_))
                                    }
        } yield result

      override def metrics: UIO[Metrics] = inFlightAndQueued.get.map(state => Metrics(state.inFlight, state.enqueued))
    }
}
