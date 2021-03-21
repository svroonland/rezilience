package nl.vroste.rezilience

import nl.vroste.rezilience.BulkheadPlatformSpecificObj.MetricsInternal
import org.HdrHistogram.{ AbstractHistogram, IntCountsHistogram }
import zio.clock.Clock
import zio.duration._
import zio.{ clock, Ref, UIO, ZManaged }

import java.time.Instant

final case class BulkheadMetrics(
  /**
   * Interval in which these metrics were collected
   */
  interval: Duration,
  /**
   * Distribution of number of calls in flight
   */
  inFlight: IntCountsHistogram,
  /**
   * Times that tasks were queued by the Bulkhead before starting execution
   */
  latency: AbstractHistogram,
  /**
   * Number of tasks that are currently being executed
   */
  currentlyInFlight: Long,
  /**
   *  Number of tasks that are currently enqueued
   */
  currentlyEnqueued: Long
) {
  import HistogramUtil._

  def meanInFlight: Double = inFlight.getMean

  def meanLatency: Double = latency.getMean

  def tasksStarted: Long = latency.getTotalCount

  override def toString: String                 =
    Seq(
      ("interval", interval.getSeconds, "s"),
      ("tasks currently enqueued", currentlyEnqueued, ""),
      ("tasks currently in flight", currentlyInFlight, ""),
      ("mean number of tasks in flight", inFlight.getMean.toInt, ""),
      ("95% number of tasks in flight", inFlight.getValueAtPercentile(95).toInt, ""),
      ("min number of tasks in flight", inFlight.getMinValue.toInt, ""),
      ("mean latency", latency.getMean.toInt, "ms"),
      ("95% latency", latency.getValueAtPercentile(95).toInt, "ms"),
      ("min latency", latency.getMinValue.toInt, "ms")
    ).map { case (name, value, unit) => s"${name}=${value}${if (unit.isEmpty) "" else " " + unit}" }.mkString(", ")

  // TODO add start time to metrics so we can pick which one is the latest for currentlyInFlight..?
  def +(that: BulkheadMetrics): BulkheadMetrics = copy(
    interval = interval plus that.interval,
    latency = mergeHistograms(latency, that.latency),
    inFlight = mergeHistograms(inFlight, that.inFlight)
  )
}

trait BulkheadPlatformSpecificObj {

  /**
   * Create a Bulkhead that periodically emits metrics
   *
   * @param maxInFlightCalls
   * @param maxQueueing
   * @param metricsInterval Interval at which metrics are emitted
   * @param sampleInterval Interval at which the number of in-flight calls is sampled
   * @return
   */
  def makeWithMetrics(
    maxInFlightCalls: Int,
    maxQueueing: Int = 32,
    onMetrics: BulkheadMetrics => UIO[Any],
    metricsInterval: Duration = 10.seconds,
    sampleInterval: Duration = 1.seconds,
    latencyHistogramSettings: HistogramSettings[Duration] = HistogramSettings(1.milli, 2.minutes)
  ): ZManaged[Clock, Nothing, Bulkhead] = {
    val inFlightHistogramSettings = HistogramSettings[Long](0, maxInFlightCalls.toLong, 2)

    def collectMetrics(currentMetrics: Ref[MetricsInternal]) =
      for {
        now         <- clock.instant
        newMetrics   = MetricsInternal.empty(now, latencyHistogramSettings, inFlightHistogramSettings)
        lastMetrics <-
          currentMetrics.getAndUpdate(metrics => newMetrics.copy(currentlyEnqueued = metrics.currentlyEnqueued))
        interval     = java.time.Duration.between(lastMetrics.start, now)
        _           <- onMetrics(lastMetrics.toUserMetrics(interval))
      } yield ()

    for {
      inner   <- Bulkhead.make(maxInFlightCalls, maxQueueing)
      now     <- clock.instant.toManaged_
      metrics <- Ref.make(MetricsInternal.empty(now, latencyHistogramSettings, inFlightHistogramSettings)).toManaged_
      _       <- MetricsUtil.runCollectMetricsLoop(metrics, metricsInterval)(collectMetrics)
    } yield inner
  }

}

object BulkheadPlatformSpecificObj {
  final case class MetricsInternal(
    start: Instant,
    inFlight: IntCountsHistogram,
    latency: AbstractHistogram,
    currentlyInFlight: Long,
    tasksEnqueued: Long,
    currentlyEnqueued: Long
  ) {
    import HistogramUtil._

    def toUserMetrics(interval: Duration): BulkheadMetrics =
      BulkheadMetrics(interval, inFlight, latency, tasksEnqueued, currentlyEnqueued)

    def taskStarted(latencySample: Duration): MetricsInternal = copy(
      latency = addToHistogram(latency, Seq(Math.max(0, latencySample.toMillis))),
      currentlyEnqueued = currentlyEnqueued - 1
    )

    def taskInterrupted = copy(currentlyEnqueued = currentlyEnqueued - 1)

    def enqueueTask: MetricsInternal =
      copy(tasksEnqueued = tasksEnqueued + 1, currentlyEnqueued = currentlyEnqueued + 1)
  }

  object MetricsInternal {
    def empty(now: Instant, latencySettings: HistogramSettings[Duration], inFlightSettings: HistogramSettings[Long]) =
      MetricsInternal(
        start = now,
        latency = new IntCountsHistogram(
          latencySettings.min.toMillis,
          latencySettings.max.toMillis,
          latencySettings.significantDigits
        ),
        inFlight =
          new IntCountsHistogram(inFlightSettings.min, inFlightSettings.max, inFlightSettings.significantDigits),
        tasksEnqueued = 0,
        currentlyEnqueued = 0,
        currentlyInFlight = 0
      )
  }

}
