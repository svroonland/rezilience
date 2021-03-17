package nl.vroste.rezilience

import org.HdrHistogram.{ AbstractHistogram, IntCountsHistogram }
import zio.{ clock, Ref, Schedule, UIO, ZIO, ZManaged }
import zio.clock.Clock
import zio.duration.{ durationInt, Duration }

import java.time.Instant

private[rezilience] final case class RateLimiterMetricsInternal(
  start: Instant,
  latency: AbstractHistogram
) {

  def toUserMetrics(interval: Duration): RateLimiterMetrics = RateLimiterMetrics(interval, latency)
}

private[rezilience] object RateLimiterMetricsInternal {
  // Between 1 ms and 10 minutes
  private val emptyLatency = new IntCountsHistogram(1, 6000000, 2)

  def empty(now: Instant) = RateLimiterMetricsInternal(now, emptyLatency)
}

final case class RateLimiterMetrics(
  /**
   * Interval in which these metrics were collected
   */
  interval: Duration,
  /**
   * Times that tasks were queued by the RateLimiter before starting execution
   */
  latency: AbstractHistogram
)

object RateLimiterMetrics {
  // Between 1 ms and 10 minutes
  private val emptyLatency = new IntCountsHistogram(1, 6000000, 2)

  val empty = RateLimiterMetrics(0.seconds, emptyLatency)

}

object HistogramUtil {
  def mergeHistograms[T <: AbstractHistogram](h1: T, h2: T): T = {
    val newHist = h1.copy()
    newHist.add(h2)
    newHist.asInstanceOf[T]
  }

  def addToHistogram[T <: AbstractHistogram](hist: T, values: Seq[Long]): T = {
    val newHist = hist.copy().asInstanceOf[T]
    values.foreach(newHist.recordValue)
    newHist
  }
}

trait RateLimiterPlatformSpecificObj {

  def makeWithMetrics(
    max: Long,
    interval: Duration = 1.second,
    metricsInterval: Duration = 10.seconds,
    onMetrics: RateLimiterMetrics => UIO[Unit]
  ): ZManaged[Clock, Nothing, RateLimiter] = {

    def collectMetrics(currentMetrics: Ref[RateLimiterMetricsInternal]) =
      for {
        now         <- clock.instant
        newMetrics   = RateLimiterMetricsInternal.empty(now)
        lastMetrics <- currentMetrics.getAndUpdate(_ => newMetrics)
        interval     = java.time.Duration.between(lastMetrics.start, now)
        _           <- onMetrics(lastMetrics.toUserMetrics(interval))
      } yield ()

    def runCollectMetricsLoop(metrics: Ref[RateLimiterMetricsInternal]) =
      collectMetrics(metrics)
        .delay(metricsInterval)
        .repeat(Schedule.fixed(metricsInterval))
        .forkManaged
        .ensuring(collectMetrics(metrics))

    for {
      inner   <- RateLimiter.make(max, interval)
      now     <- clock.instant.toManaged_
      metrics <- Ref.make(RateLimiterMetricsInternal.empty(now)).toManaged_
      _       <- runCollectMetricsLoop(metrics)
    } yield new RateLimiter {
      override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, E, A] = inner.apply(task)
    }
  }
}
