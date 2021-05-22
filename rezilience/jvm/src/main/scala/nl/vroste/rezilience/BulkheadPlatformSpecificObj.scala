package nl.vroste.rezilience

import nl.vroste.rezilience.BulkheadPlatformSpecificObj.MetricsInternal
import org.HdrHistogram.{ AbstractHistogram, IntCountsHistogram }
import zio.clock.Clock
import zio.duration._
import zio.{ clock, Ref, Schedule, UIO, ZIO, ZManaged }

import java.time.Instant

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
    val inFlightHistogramSettings = HistogramSettings[Long](1, maxInFlightCalls.toLong, 2)
    val enqueuedHistogramSettings = HistogramSettings[Long](1, maxQueueing.toLong, 2)

    def makeNewMetrics = clock.instant.map(now =>
      MetricsInternal.empty(now, latencyHistogramSettings, inFlightHistogramSettings, enqueuedHistogramSettings)
    )

    def collectMetrics(currentMetrics: Ref[MetricsInternal]) =
      for {
        newMetrics  <- makeNewMetrics
        lastMetrics <-
          currentMetrics.getAndUpdate(metrics =>
            newMetrics.copy(
              currentlyEnqueued = metrics.currentlyEnqueued,
              currentlyInFlight = metrics.currentlyInFlight
            )
          )
        interval     = java.time.Duration.between(lastMetrics.start, newMetrics.start)
        _           <- onMetrics(lastMetrics.toUserMetrics(interval))
      } yield ()

    for {
      inner   <- Bulkhead.make(maxInFlightCalls, maxQueueing)
      metrics <- makeNewMetrics.flatMap(Ref.make).toManaged_
      _       <- MetricsUtil.runCollectMetricsLoop(metrics, metricsInterval)(collectMetrics)
      _       <- metrics
                   .update(_.sampleCurrently)
                   .repeat(Schedule.fixed(sampleInterval))
                   .delay(sampleInterval)
                   .forkManaged
      env     <- ZManaged.environment[Clock]
    } yield new Bulkhead {
      override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, Bulkhead.BulkheadError[E], A] = for {
        enqueueTime <- clock.instant.provide(env)
        // Keep track of whether the task was started to have correct statistics under interruption
        started     <- Ref.make(false)
        result      <- metrics
                         .update(_.enqueueTask)
                         .toManaged(_ => metrics.update(_.taskInterrupted).unlessM(started.get))
                         .use_ {
                           inner.apply {
                             for {
                               startTime <- clock.instant.provide(env)
                               latency    = java.time.Duration.between(enqueueTime, startTime)
                               _         <- metrics.update(_.taskStarted(latency)).ensuring(started.set(true))
                               result    <- task.ensuring(metrics.update(_.taskCompleted))
                             } yield result
                           }
                         }
      } yield result
    }
  }

}

private[rezilience] object BulkheadPlatformSpecificObj extends BulkheadPlatformSpecificObj {
  final case class MetricsInternal(
    start: Instant,
    inFlight: IntCountsHistogram,
    enqueued: IntCountsHistogram,
    latency: AbstractHistogram,
    currentlyInFlight: Long,
    currentlyEnqueued: Long
  ) {
    import HistogramUtil._

    def toUserMetrics(interval: Duration): BulkheadMetrics =
      BulkheadMetrics(interval, inFlight, enqueued, latency, currentlyInFlight, currentlyEnqueued)

    def taskStarted(latencySample: Duration): MetricsInternal = copy(
      latency = addToHistogram(latency, Seq(Math.max(0, latencySample.toMillis))),
      currentlyEnqueued = currentlyEnqueued - 1,
      currentlyInFlight = currentlyInFlight + 1
    )

    def taskCompleted: MetricsInternal = copy(
      currentlyInFlight = currentlyInFlight - 1
    )

    def taskInterrupted = copy(currentlyEnqueued = currentlyEnqueued - 1)

    def enqueueTask: MetricsInternal =
      copy(currentlyEnqueued = currentlyEnqueued + 1)

    def sampleCurrently: MetricsInternal = copy(
      inFlight = addToHistogram(inFlight, Seq(currentlyInFlight)),
      enqueued = addToHistogram(enqueued, Seq(currentlyEnqueued))
    )
  }

  object MetricsInternal {
    def empty(
      now: Instant,
      latencySettings: HistogramSettings[Duration],
      inFlightSettings: HistogramSettings[Long],
      enqueuedSettings: HistogramSettings[Long]
    ) =
      MetricsInternal(
        start = now,
        latency = new IntCountsHistogram(
          latencySettings.min.toMillis,
          latencySettings.max.toMillis,
          latencySettings.significantDigits
        ),
        inFlight =
          new IntCountsHistogram(inFlightSettings.min, inFlightSettings.max, inFlightSettings.significantDigits),
        enqueued =
          new IntCountsHistogram(enqueuedSettings.min, enqueuedSettings.max, enqueuedSettings.significantDigits),
        currentlyEnqueued = 0,
        currentlyInFlight = 0
      )
  }

}
