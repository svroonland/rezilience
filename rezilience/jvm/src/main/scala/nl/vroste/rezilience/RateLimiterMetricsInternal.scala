package nl.vroste.rezilience

import org.HdrHistogram.IntCountsHistogram
import zio.Chunk
import zio.duration.Duration

import java.time.Instant

private[rezilience] final case class RateLimiterMetricsInternal(
  start: Instant,
  latency: Chunk[Long],
  tasksEnqueued: Long,
  currentlyEnqueued: Long
) {
  def toUserMetrics(interval: Duration, settings: HistogramSettings[Duration]): RateLimiterMetrics = {
    val latencyHistogram =
      new IntCountsHistogram(settings.min.toMillis, settings.max.toMillis, settings.significantDigits)
    latency.foreach(latencyHistogram.recordValue)
    RateLimiterMetrics(interval, latencyHistogram, tasksEnqueued, currentlyEnqueued)
  }

  def taskStarted(latencySample: Duration): RateLimiterMetricsInternal = copy(
    latency = latency :+ Math.max(0, latencySample.toMillis),
    currentlyEnqueued = currentlyEnqueued - 1
  )

  def taskInterrupted: RateLimiterMetricsInternal = copy(currentlyEnqueued = currentlyEnqueued - 1)

  def enqueueTask: RateLimiterMetricsInternal =
    copy(tasksEnqueued = tasksEnqueued + 1, currentlyEnqueued = currentlyEnqueued + 1)
}

private[rezilience] object RateLimiterMetricsInternal {
  def empty(now: Instant) =
    RateLimiterMetricsInternal(
      start = now,
      latency = Chunk.empty,
      tasksEnqueued = 0,
      currentlyEnqueued = 0
    )
}
