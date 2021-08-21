package nl.vroste.rezilience

import org.HdrHistogram.IntCountsHistogram
import zio.Chunk
import zio.duration.Duration

import java.time.Instant

private[rezilience] final case class BulkheadMetricsInternal(
  start: Instant,
  inFlight: Chunk[Long],
  enqueued: Chunk[Long],
  latency: Chunk[Long],
  currentlyInFlight: Long,
  currentlyEnqueued: Long
) {
  def taskStarted(latencySample: Duration): BulkheadMetricsInternal = copy(
    latency = latency :+ Math.max(0, latencySample.toMillis),
    currentlyEnqueued = currentlyEnqueued - 1,
    currentlyInFlight = currentlyInFlight + 1
  )

  def taskCompleted: BulkheadMetricsInternal = copy(
    currentlyInFlight = currentlyInFlight - 1
  )

  def taskInterrupted: BulkheadMetricsInternal = copy(currentlyEnqueued = currentlyEnqueued - 1)

  def enqueueTask: BulkheadMetricsInternal =
    copy(currentlyEnqueued = currentlyEnqueued + 1)

  def sampleCurrently: BulkheadMetricsInternal = copy(
    inFlight = inFlight :+ currentlyInFlight,
    enqueued = enqueued :+ currentlyEnqueued
  )

  def toUserMetrics(
    interval: Duration,
    latencySettings: HistogramSettings[Duration],
    inFlightSettings: HistogramSettings[Long],
    enqueuedSettings: HistogramSettings[Long]
  ): BulkheadMetrics = {
    val inFlightHistogram =
      new IntCountsHistogram(inFlightSettings.min, inFlightSettings.max, inFlightSettings.significantDigits)
    inFlight.foreach(inFlightHistogram.recordValue)

    val enqueuedHistogram =
      new IntCountsHistogram(enqueuedSettings.min, enqueuedSettings.max, enqueuedSettings.significantDigits)
    enqueued.foreach(enqueuedHistogram.recordValue)

    val latencyHistogram = new IntCountsHistogram(
      latencySettings.min.toMillis,
      latencySettings.max.toMillis,
      latencySettings.significantDigits
    )
    latency.foreach(latencyHistogram.recordValue)

    BulkheadMetrics(
      interval,
      inFlightHistogram,
      enqueuedHistogram,
      latencyHistogram,
      currentlyInFlight,
      currentlyEnqueued
    )
  }
}

private[rezilience] object BulkheadMetricsInternal {
  def empty(now: Instant): BulkheadMetricsInternal =
    BulkheadMetricsInternal(
      start = now,
      latency = Chunk.empty,
      inFlight = Chunk.empty,
      enqueued = Chunk.empty,
      currentlyEnqueued = 0,
      currentlyInFlight = 0
    )
}
