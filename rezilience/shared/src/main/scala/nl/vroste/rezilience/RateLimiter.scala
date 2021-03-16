package nl.vroste.rezilience
import zio.duration.{ durationInt, Duration }
import zio.stream.ZStream
import zio._
import zio.clock.Clock

/**
 * Limits the number of calls to a resource to a maximum amount in some interval
 *
 * Uses a token bucket algorithm
 *
 * Note that only the moment of starting the effect is rate limited: the number of concurrent executions is not bounded.
 * For that you may use a Bulkhead
 *
 * Calls are queued up in an unbounded queue until capacity becomes available.
 */
trait RateLimiter { self =>

  /**
   * Execute the task with RateLimiter protection
   *
   * The effect returned by this method can be safely interrupted. If the task is still waiting in the rate limiter queue,
   * the task will not begin execution anymore. However the interrupted call will still need to pass through the rate limiter throttle,
   * so the next queued call will possibly be delayed according to rate limiting parameters.
   *
   * If the task has already started execution, interruption will be completed when the task is interrupted.
   *
   * @param task Task to execute. When the rate limit is exceeded, the call will be postponed.
   */
  def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, E, A]

  def toPolicy: Policy[Any] = new Policy[Any] {
    override def apply[R, E1 <: Any, A](f: ZIO[R, E1, A]): ZIO[R, Policy.PolicyError[E1], A] =
      self(f).mapError(Policy.WrappedError(_))
  }
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
  def make(max: Int, interval: Duration = 1.second): ZManaged[Clock, Nothing, RateLimiter] = for {
    q <- Queue.unbounded[(Ref[Boolean], UIO[Any])].toManaged_
    _ <- ZStream
           .fromQueue(q, maxChunkSize = max)
           .filterM { case (interrupted, effect @ _) => interrupted.get.map(!_) }
           .throttleShape(max.toLong, interval, max.toLong)(_.size.toLong)
           .mapMParUnordered(Int.MaxValue) { case (interrupted @ _, effect) => effect }
           .runDrain
           .forkManaged
  } yield new RateLimiter {
    override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, E, A] =
      withInterruptableEffect(task)(q.offer)
  }

  private def withInterruptableEffect[R, E, A](
    task: ZIO[R, E, A]
  )(f: ((Ref[Boolean], UIO[Any])) => UIO[Any]): ZIO[R, E, A] = for {
    p              <- Promise.make[E, A]
    interrupted    <- Promise.make[Nothing, Unit]
    env            <- ZIO.environment[R]
    started        <- Semaphore.make(1)
    interruptedRef <- Ref.make(false)
    effect          = started
                        .withPermit(interrupted.await raceFirst task.foldM(p.fail, p.succeed).provide(env))
                        .unlessM(interrupted.isDone)
    result         <- (f((interruptedRef, effect)) *> p.await).onInterrupt {
                        interrupted.succeed(()) <* interruptedRef.set(true) *>
                          // When task is already executing, this means we have to wait for interruption to complete
                          started.withPermit(ZIO.unit)
                      }
  } yield result
}
