package nl.vroste.rezilience

import nl.vroste.rezilience.Bulkhead._
import zio._
import zio.metrics.MetricKeyType.Histogram.Boundaries
import zio.metrics.{ Metric, MetricKeyType, MetricLabel }
import zio.stream.ZStream
import zio.Duration

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

  final case class BulkheadException[E](error: BulkheadError[E]) extends Exception("Bulkhead error")

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
   *   Maximum of concurrent executing calls
   * @param maxQueueing
   *   Maximum queueing calls
   * @return
   */
  def make(maxInFlightCalls: Int, maxQueueing: Int = 32): ZIO[Scope, Nothing, Bulkhead] =
    for {
      queue             <- Queue
                             .bounded[UIO[Unit]](Util.nextPow2(maxQueueing))
      inFlightAndQueued <- Ref.make(State(0, 0))
      onStart            = inFlightAndQueued.update(_.startProcess)
      onEnd              = inFlightAndQueued.update(_.endProcess)
      _                 <- ZStream
                             .fromQueueWithShutdown(queue)
                             .mapZIOPar(maxInFlightCalls) { task =>
                               ZIO.acquireReleaseWith(onStart)(_ => onEnd)(_ => task)
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
    }

  final case class BulkheadMetrics(
    callsInFlight: Metric.Histogram[Double],
    callsEnqueued: Metric.Histogram[Double],
    callsCompleted: Metric.Counter[Long],
    callsRejected: Metric.Counter[Long],
    queueTime: Metric.Histogram[Double]
  )

  /**
   * Takes an existing Bulkhead and returns a new one that records metrics
   *
   * Metrics are
   *   - rezilience_bulkhead_calls_in_flight: histogram of number of calls in-flight
   *   - rezilience_bulkhead_calls_enqueued: histogram of number of calls enqueued
   *   - rezilience_bulkhead_calls_completed: number of calls that were completed (either succesfully or failed)
   *   - rezilience_bulkhead_calls_rejected: number of calls rejected because of a full queue
   *   - rezilience_bulkhead_queue_time: histogram of queueing times (in nanoseconds)
   *
   * Be sure to use only the returned Bulkhead and not the one given as parameter, otherwise no metrics will be
   * recorded. Recommended usage is to create it in go, eg `cb <- Bulkhead.make(10, 32).flatMap(Bulkhead.withMetrics(_,
   * labels))`
   *
   * @param bulkhead
   *   Existing Bulkhead
   * @param labels
   *   Set of labels to annotate metrics with, to distinguish this Bulkhead from others in the same application.
   *
   * @return
   *   Bulkhead that records metrics
   */
  def withMetrics(
    bulkhead: Bulkhead,
    labels: Set[MetricLabel],
    sampleInterval: Duration = 1.second,
// TODO find suitable boundaries values
    boundariesCalls: Boundaries = MetricKeyType.Histogram.Boundaries.exponential(0, 10, 11),
    boundariesQueueTime: Boundaries = MetricKeyType.Histogram.Boundaries.linear(0, 10, 11)
  ) = {
    val metrics = BulkheadMetrics(
      callsInFlight = Metric.histogram("rezilience_bulkhead_calls_in_flight", boundariesCalls).tagged(labels),
      callsEnqueued = Metric.histogram("rezilience_bulkhead_calls_enqueued", boundariesCalls).tagged(labels),
      callsCompleted = Metric.counter("rezilience_bulkhead_calls_completed").tagged(labels),
      callsRejected = Metric.counter("rezilience_bulkhead_calls_rejected").tagged(labels),
      queueTime = Metric.histogram("rezilience_bulkhead_queue_time", boundariesQueueTime).tagged(labels)
    )

    def sampleMetrics(state: BulkheadWithMetricsState) =
      for {
        _ <- metrics.callsInFlight.update(state.inFlight.toDouble)
        _ <- metrics.callsEnqueued.update(state.enqueued.toDouble)
      } yield ()

    for {
      state <- Ref.make(BulkheadWithMetricsState(0, 0))
      _     <- (state.get.flatMap(sampleMetrics)).repeat(Schedule.fixed(sampleInterval)).forkScoped
    } yield new BulkheadWithMetrics(bulkhead, metrics, state)

  }

  private[rezilience] case class BulkheadWithMetricsState(enqueued: Int, inFlight: Int) {
    def enqueue: BulkheadWithMetricsState                  = copy(enqueued + 1, inFlight)
    def start: BulkheadWithMetricsState                    = copy(enqueued - 1, inFlight + 1)
    def complete: BulkheadWithMetricsState                 = copy(enqueued, inFlight - 1)
    def interruptedAfterStart: BulkheadWithMetricsState    = copy(enqueued, inFlight - 1)
    def interruptedWhileEnqueued: BulkheadWithMetricsState = copy(enqueued - 1, inFlight)
  }

  private[rezilience] class BulkheadWithMetrics(
    inner: Bulkhead,
    metrics: BulkheadMetrics,
    state: Ref[BulkheadWithMetricsState]
  ) extends Bulkhead {
    override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, Bulkhead.BulkheadError[E], A] =
      ZIO.uninterruptibleMask { outerRestore =>
        withRecordQueueTime { recordQueueTime =>
          for {
            _       <- state.update(_.enqueue)
            started <- Ref.make(false)
            result  <- outerRestore {
                         inner.apply {
                           ZIO.uninterruptibleMask { innerRestore =>
                             started.set(true) *>
                               state.update(_.start) *>
                               innerRestore {
                                 (recordQueueTime *> task <* state.update(_.complete)).onInterrupt(
                                   state.update(_.interruptedAfterStart)
                                 )
                               }
                           }
                         }.onInterrupt(ZIO.unlessZIO(started.get)(state.update(_.interruptedWhileEnqueued)))
                       }
          } yield result
        }
      }.tapBoth(
        {
          case BulkheadRejection => metrics.callsRejected.increment
          case _                 => metrics.callsCompleted.increment
        },
        _ => metrics.callsCompleted.increment
      )

    private def withRecordQueueTime[R, E, A](f: UIO[Unit] => ZIO[R, E, A]) = for {
      enqueueTime      <- ZIO.clockWith(_.instant)
      record: UIO[Unit] = for {
                            startTime <- ZIO.clockWith(_.instant)
                            queueTime  =
                              Math.max(0.0d, java.time.Duration.between(enqueueTime, startTime).toNanos.toDouble)
                            _         <- metrics.queueTime.update(queueTime)
                          } yield ()
      result           <- f(record)
    } yield result
  }
}
