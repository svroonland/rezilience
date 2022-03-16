package nl.vroste.rezilience

import zio.clock.Clock
import zio.duration._
import zio.{ clock, Ref, Schedule, URIO, ZIO, ZManaged }

trait BulkheadPlatformSpecificObj {

  /**
   * Create a Bulkhead that periodically emits metrics
   *
   * @param maxInFlightCalls
   * @param maxQueueing
   * @param metricsInterval
   *   Interval at which metrics are emitted
   * @param sampleInterval
   *   Interval at which the number of in-flight calls is sampled
   * @return
   */
  def makeWithMetrics[R1](
    maxInFlightCalls: Int,
    maxQueueing: Int = 32,
    onMetrics: BulkheadMetrics => URIO[R1, Any],
    metricsInterval: Duration = 10.seconds,
    sampleInterval: Duration = 1.seconds,
    latencyHistogramSettings: HistogramSettings[Duration] = HistogramSettings(1.milli, 2.minutes)
  ): ZManaged[Clock with R1, Nothing, Bulkhead] = {
    val inFlightHistogramSettings = HistogramSettings[Long](1, maxInFlightCalls.toLong, 2)
    val enqueuedHistogramSettings = HistogramSettings[Long](1, maxQueueing.toLong, 2)

    def makeNewMetrics = clock.instant.map(BulkheadMetricsInternal.empty)

    def collectMetrics(currentMetrics: Ref[BulkheadMetricsInternal]) =
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
        _           <- onMetrics(
                         lastMetrics.toUserMetrics(
                           interval,
                           latencyHistogramSettings,
                           inFlightHistogramSettings,
                           enqueuedHistogramSettings
                         )
                       )
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

private[rezilience] object BulkheadPlatformSpecificObj extends BulkheadPlatformSpecificObj
