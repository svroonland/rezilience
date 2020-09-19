package nl.vroste.rezilience
import zio.duration.Duration
import zio.stream.ZStream
import zio._
import zio.clock.Clock

/**
 * Limits the number of calls to a resource to a maximum amount in some interval
 *
 * Uses a token bucket algorithm
 *
 * Note that only the moment of starting the effect is rate limited: the number of concurrent executions is not bounded.
 *
 * Calls are queued up in an unbounded queue until capacity becomes available.
 */
trait RateLimiter {

  /**
   * Call the system with RateLimiter protection
   *
   * @param task Task to execute. When the rate limit is exceeded, the call will be postponed. The environment of the
   *             task i
   */
  def call[R, E, A](task: ZIO[R, E, A]): ZIO[R, E, A]
}

object RateLimiter {

  /**
   * Creates a RateLimiter as Managed resource
   *
   * Note that the maximum number of calls is spread out over the interval, i.e. 10 calls per second
   * means that 1 call can be made every 100 ms. Up to `max` calls can be saved up. The maximum
   * is immediately available when starting the RateLimiter
   *
   * @param max Maximum number of calls in each interval
   * @param interval Interval duration
   * @return RateLimiter
   */
  def make(max: Long, interval: Duration): ZManaged[Clock, Nothing, RateLimiter] = for {
    q <- Queue.unbounded[UIO[Any]].toManaged_
    _ <- ZStream
           .fromQueue(q)
           .throttleShape(max, interval, max)(_.size.toLong)
           .mapMParUnordered(Int.MaxValue)(identity)
           .runDrain
           .forkManaged
  } yield new RateLimiter {
    override def call[R, E, A](task: ZIO[R, E, A]): ZIO[R, E, A] = for {
      p      <- Promise.make[E, A]
      env    <- ZIO.environment[R]
      effect  = task.foldM(p.fail, p.succeed).provide(env)
      _      <- q.offer(effect)
      result <- p.await
    } yield result
  }
}
