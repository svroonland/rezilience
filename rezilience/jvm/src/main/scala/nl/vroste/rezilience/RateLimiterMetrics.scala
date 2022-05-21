package nl.vroste.rezilience

import org.HdrHistogram.{ AbstractHistogram, IntCountsHistogram }
import zio.duration._

final case class RateLimiterMetrics(
  /**
   * Interval in which these metrics were collected
   */
  interval: Duration,
  /**
   * Times that tasks were queued by the RateLimiter before starting execution
   */
  latency: AbstractHistogram,
  /**
   * Number of tasks that were enqueued in this metrics interval
   */
  tasksEnqueued: Long,
  /**
   * Number of tasks that are currently enqueued
   */
  currentlyEnqueued: Long
) {
  import HistogramUtil._
  val tasksStarted = latency.getTotalCount

  override def toString: String =
    Seq(
      ("interval", interval.getSeconds, "s"),
      ("tasks enqueued in interval", tasksEnqueued, ""),
      ("tasks currently enqueued", currentlyEnqueued, ""),
      ("tasks started", tasksStarted, ""),
      ("mean latency", latency.getMean.toInt, "ms"),
      ("95% latency", latency.getValueAtPercentile(95).toInt, "ms"),
      ("min latency", latency.getMinValue.toInt, "ms")
    ).map { case (name, value, unit) => s"${name}=${value}${if (unit.isEmpty) "" else " " + unit}" }.mkString(", ")

  /**
   * Combines the metrics and their histograms
   *
   * currentlyEnqueued is taken from the `that` parameter, so be sure to use this method as `oldMetrics +
   * latestMetrics`.
   */
  def +(that: RateLimiterMetrics): RateLimiterMetrics = RateLimiterMetrics(
    interval = interval plus that.interval,
    latency = mergeHistograms(latency, that.latency),
    tasksEnqueued = tasksEnqueued + that.tasksEnqueued,
    currentlyEnqueued = that.currentlyEnqueued
  )
}

object RateLimiterMetrics {
  private val emptyLatency = new IntCountsHistogram(HistogramSettings.default.significantDigits)

  val empty = RateLimiterMetrics(0.seconds, emptyLatency, 0, 0)

}
