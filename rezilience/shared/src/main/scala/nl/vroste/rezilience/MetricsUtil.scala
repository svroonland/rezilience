package nl.vroste.rezilience

import zio.clock.Clock
import zio.duration.Duration
import zio.{ Fiber, Ref, Schedule, ZIO, ZManaged }

private[rezilience] object MetricsUtil {
  def runCollectMetricsLoop[R](interval: Duration)(
    emitAndResetMetrics: ZIO[R, Nothing, Unit]
  ): ZManaged[Clock with R, Nothing, Fiber.Runtime[Nothing, Long]] =
    emitAndResetMetrics
      .repeat(Schedule.fixed(interval))
      .delay(interval)
      .forkManaged
      .ensuring(emitAndResetMetrics)
}
