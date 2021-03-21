package nl.vroste.rezilience

import zio.clock.Clock
import zio.duration.Duration
import zio.{ Fiber, Ref, Schedule, ZIO, ZManaged }

object MetricsUtil {
  def runCollectMetricsLoop[T, R](metrics: Ref[T], interval: Duration)(
    emitAndResetMetrics: Ref[T] => ZIO[R, Nothing, Unit]
  ): ZManaged[Clock with R, Nothing, Fiber.Runtime[Nothing, Long]] =
    emitAndResetMetrics(metrics)
      .repeat(Schedule.fixed(interval))
      .delay(interval)
      .forkManaged
      .ensuring(emitAndResetMetrics(metrics))
}
