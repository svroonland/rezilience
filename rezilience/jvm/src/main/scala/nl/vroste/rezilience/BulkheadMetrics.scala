package nl.vroste.rezilience

import org.HdrHistogram.{ AbstractHistogram, IntCountsHistogram }
import zio.duration.Duration

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
   * Distribution of number of calls in flight
   */
  enqueued: IntCountsHistogram,
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

  def meanEnqueued: Double = enqueued.getMean

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
      ("max number of tasks in flight", inFlight.getMaxValue.toInt, ""),
      ("mean number of tasks enqueued", enqueued.getMean.toInt, ""),
      ("95% number of tasks enqueued", enqueued.getValueAtPercentile(95).toInt, ""),
      ("min number of tasks enqueued", enqueued.getMinValue.toInt, ""),
      ("max number of tasks enqueued", enqueued.getMaxValue.toInt, ""),
      ("mean latency", latency.getMean.toInt, "ms"),
      ("95% latency", latency.getValueAtPercentile(95).toInt, "ms"),
      ("min latency", latency.getMinValue.toInt, "ms"),
      ("max latency", latency.getMaxValue.toInt, "ms")
    ).map { case (name, value, unit) => s"${name}=${value}${if (unit.isEmpty) "" else " " + unit}" }.mkString(", ")

  // TODO add start time to metrics so we can pick which one is the latest for currentlyInFlight..?
  def +(that: BulkheadMetrics): BulkheadMetrics = copy(
    interval = interval plus that.interval,
    latency = mergeHistograms(latency, that.latency),
    inFlight = mergeHistograms(inFlight, that.inFlight),
    enqueued = mergeHistograms(enqueued, that.enqueued)
  )
}

object BulkheadMetrics {
  private val emptyInFlight = new IntCountsHistogram(1, 10, 2)
  private val emptyEnqueued = new IntCountsHistogram(1, 10, 2)
  private val emptyLatency  = new IntCountsHistogram(1, 6000000, 2)

  val empty = BulkheadMetrics(0.seconds, emptyInFlight, emptyEnqueued, emptyLatency, 0, 0)

}
