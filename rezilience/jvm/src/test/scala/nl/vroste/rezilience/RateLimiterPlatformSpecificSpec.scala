package nl.vroste.rezilience

import zio.test._
import zio.duration._
import zio.UIO
import zio.clock
import zio.Schedule
import zio.clock.Clock
import zio.random.Random
import zio.ZIO

object RateLimiterPlatformSpecificSpec extends DefaultRunnableSpec {
  override def spec = suite("RateLimiter")(
    suite("metrics")(
      testM("emits metrics") {
        RateLimiterPlatformSpecificObj
          .makeWithMetrics(10, 1.second, 5.second, onMetrics = metrics => UIO(println(metrics)))
          .use { rl =>
            for {
              _ <- rl(clock.instant.flatMap(now => UIO(println(now)))).fork
                     .repeat(Schedule.fixed(100.millis))
              _ <- ZIO.sleep(20.seconds)
            } yield assertCompletes
          }
      }
    )
  ).provideCustomLayer(Clock.live ++ Random.live)
}
