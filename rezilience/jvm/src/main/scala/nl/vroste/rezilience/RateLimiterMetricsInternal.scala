package nl.vroste.rezilience

import org.HdrHistogram.IntCountsHistogram
import zio.Chunk
import zio.duration.Duration
import zio.stm.{ TRef, USTM, ZSTM }

import java.time.Instant

private[rezilience] final case class RateLimiterMetricsInternal(
  start: TRef[Instant],
  latency: TRef[Chunk[Long]],
  tasksEnqueued: TRef[Long],
  currentlyEnqueued: TRef[Long]
) {
  def toUserMetrics(interval: Duration, settings: HistogramSettings[Duration]): USTM[RateLimiterMetrics] =
    for {
      latency           <- latency.get
      tasksEnqueued     <- tasksEnqueued.get
      currentlyEnqueued <- currentlyEnqueued.get
    } yield {
      val latencyHistogram =
        (settings.min zip settings.max).fold(new IntCountsHistogram(settings.significantDigits)) { case (min, max) =>
          val hist = new IntCountsHistogram(
            min.toMillis,
            max.toMillis,
            settings.significantDigits
          )
          if (settings.autoResize) hist.setAutoResize(true)
          hist
        }
      latency.foreach(latencyHistogram.recordValue)
      RateLimiterMetrics(interval, latencyHistogram, tasksEnqueued, currentlyEnqueued)
    }

  def taskStarted(latencySample: Duration): USTM[Unit] =
    latency.update(_ :+ Math.max(0, latencySample.toMillis)) *>
      currentlyEnqueued.update(_ - 1)

  def taskInterrupted: USTM[Unit] = currentlyEnqueued.update(_ - 1)

  def enqueueTask: USTM[Unit] =
    tasksEnqueued.update(_ + 1) *> currentlyEnqueued.update(_ + 1)

  def reset(now: Instant): USTM[Unit] =
    start.set(now) *>
      tasksEnqueued.set(0L) *>
      currentlyEnqueued.set(0L) *>
      latency.set(Chunk.empty)
}

private[rezilience] object RateLimiterMetricsInternal {
  def makeEmpty(now: Instant): ZSTM[Any, Nothing, RateLimiterMetricsInternal] = for {
    start             <- TRef.make(now)
    latency           <- TRef.make[Chunk[Long]](Chunk.empty)
    tasksEnqueued     <- TRef.make(0L)
    currentlyEnqueued <- TRef.make(0L)
  } yield RateLimiterMetricsInternal(
    start = start,
    latency = latency,
    tasksEnqueued = tasksEnqueued,
    currentlyEnqueued = currentlyEnqueued
  )
}
