package nl.vroste.rezilience

import org.HdrHistogram.IntCountsHistogram
import zio.Chunk
import zio.duration.Duration
import zio.stm.{ TRef, USTM }

import java.time.Instant

private[rezilience] final case class BulkheadMetricsInternal(
  start: TRef[Instant],
  inFlight: TRef[Chunk[Long]],
  enqueued: TRef[Chunk[Long]],
  latency: TRef[Chunk[Long]],
  currentlyInFlight: TRef[Long],
  currentlyEnqueued: TRef[Long]
) {
  def taskStarted(latencySample: Duration): USTM[Unit] =
    (latency.update(_ :+ Math.max(0, latencySample.toMillis)) zip
      currentlyEnqueued.update(_ - 1) zip
      currentlyInFlight.update(_ + 1)).unit

  def taskCompleted: USTM[Unit]   = currentlyInFlight.update(_ - 1)
  def taskInterrupted: USTM[Unit] = currentlyEnqueued.update(_ - 1)

  def enqueueTask: USTM[Unit] = currentlyEnqueued.update(_ + 1)

  def sampleCurrently: USTM[Unit] =
    currentlyInFlight.get.flatMap(nr => inFlight.update(_ :+ nr)) *>
      currentlyEnqueued.get.flatMap(nr => enqueued.update(_ :+ nr))

  def toUserMetrics(
    interval: Duration,
    latencySettings: HistogramSettings[Duration],
    inFlightSettings: HistogramSettings[Long],
    enqueuedSettings: HistogramSettings[Long]
  ): USTM[BulkheadMetrics] = for {
    inFlight          <- inFlight.get
    enqueued          <- enqueued.get
    latency           <- latency.get
    currentlyInFlight <- currentlyInFlight.get
    currentlyEnqueued <- currentlyEnqueued.get
  } yield {
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
  def makeEmpty(now: Instant): USTM[BulkheadMetricsInternal] =
    for {
      start             <- TRef.make(now)
      latency           <- TRef.make[Chunk[Long]](Chunk.empty)
      inFlight          <- TRef.make[Chunk[Long]](Chunk.empty)
      enqueued          <- TRef.make[Chunk[Long]](Chunk.empty)
      currentlyEnqueued <- TRef.make(0L)
      currentlyInFlight <- TRef.make(0L)
    } yield BulkheadMetricsInternal(start, inFlight, enqueued, latency, currentlyEnqueued, currentlyInFlight)
}
