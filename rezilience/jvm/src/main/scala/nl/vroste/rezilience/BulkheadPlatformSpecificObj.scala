package nl.vroste.rezilience

import zio.clock.Clock
import zio.duration._
import zio.stm.ZSTM
import zio.{ clock, Chunk, Ref, Schedule, URIO, ZIO, ZManaged }

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
    inner: Bulkhead,
    onMetrics: BulkheadMetrics => URIO[R1, Any],
    metricsInterval: Duration = 10.seconds,
    sampleInterval: Duration = 1.seconds,
    // TODO automatically growing histograms, with some sensible defaults
    latencyHistogramSettings: HistogramSettings[Duration] = HistogramSettings(1.milli, 2.minutes),
    inFlightHistogramSettings: HistogramSettings[Long] = HistogramSettings(1, 100, 2),
    enqueuedHistogramSettings: HistogramSettings[Long] = HistogramSettings(1, 32, 2)
  ): ZManaged[Clock with R1, Nothing, Bulkhead] = {
    def makeNewMetrics = clock.instant
      .flatMap(BulkheadMetricsInternal.makeEmpty(_).commit)

    def collectMetrics(currentMetrics: BulkheadMetricsInternal) =
      for {
        now         <- clock.instant
        userMetrics <- ZSTM.atomically {
                         for {
                           lastMetricsStart <- currentMetrics.start.get
                           interval          = java.time.Duration.between(lastMetricsStart, now)

                           userMetrics <- currentMetrics.toUserMetrics(
                                            interval,
                                            latencyHistogramSettings,
                                            inFlightHistogramSettings,
                                            enqueuedHistogramSettings
                                          )

                           // Reset collectors
                           _           <- currentMetrics.start.set(now)
                           _           <- currentMetrics.inFlight.set(Chunk.empty)
                           _           <- currentMetrics.enqueued.set(Chunk.empty)
                           _           <- currentMetrics.latency.set(Chunk.empty)
                         } yield userMetrics
                       }
        _           <- onMetrics(userMetrics)
      } yield ()

    for {
      metrics <- makeNewMetrics.toManaged_
      _       <- MetricsUtil.runCollectMetricsLoop(metricsInterval)(collectMetrics(metrics))
      _       <- metrics.sampleCurrently.commit
                   .repeat(Schedule.fixed(sampleInterval))
                   .delay(sampleInterval)
                   .forkManaged
      env     <- ZManaged.environment[Clock]
    } yield new Bulkhead {
      override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, Bulkhead.BulkheadError[E], A] = for {
        enqueueTime <- clock.instant.provide(env)
        // Keep track of whether the task was started to have correct statistics under interruption
        started     <- Ref.make(false)
        result      <- metrics.enqueueTask.commit
                         .toManaged(_ => metrics.taskInterrupted.commit.unlessM(started.get))
                         .use_ {
                           inner.apply {
                             for {
                               startTime <- clock.instant.provide(env)
                               latency    = java.time.Duration.between(enqueueTime, startTime)
                               _         <- metrics.taskStarted(latency).commit.ensuring(started.set(true))
                               result    <- task.ensuring(metrics.taskCompleted.commit)
                             } yield result
                           }
                         }
      } yield result
    }
  }

}

private[rezilience] object BulkheadPlatformSpecificObj extends BulkheadPlatformSpecificObj
