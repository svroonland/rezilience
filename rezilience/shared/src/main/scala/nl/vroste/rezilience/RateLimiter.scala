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
   * The effect returned by this method can be interrupted, which is handled as follows:
   *   - If the task is still waiting in the rate limiter queue, it will not start execution. It will also not count for
   *     the rate limiting or hold back other uninterrupted queued tasks.
   *   - If the task has already started executing, interruption will interrupt the task and will complete when the
   *     task's interruption is complete.
   *
   * @param task
   *   Task to execute. When the rate limit is exceeded, the call will be postponed.
   */
  def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, E, A]

  def toPolicy: Policy[Any] = new Policy[Any] {
    override def apply[R, E1 <: Any, A](f: ZIO[R, E1, A]): ZIO[R, Policy.PolicyError[E1], A] =
      self(f).mapError(Policy.WrappedError(_))
  }
}

object RateLimiter extends RateLimiterPlatformSpecificObj {

  /**
   * Creates a RateLimiter as Managed resource
   *
   * Note that the maximum number of calls is spread out over the interval, i.e. 10 calls per second means that 1 call
   * can be made every 100 ms. Up to `max` calls can be saved up. The maximum is immediately available when starting the
   * RateLimiter
   *
   * @param max
   *   Maximum number of calls in each interval
   * @param interval
   *   Interval duration
   * @return
   *   RateLimiter
   */
  def make(max: Int, interval: Duration = 1.second): ZManaged[Clock, Nothing, RateLimiter] =
    for {
      q <- Queue
             .bounded[(Ref[Boolean], UIO[Any])](zio.internal.RingBuffer.nextPow2(max))
             .toManaged_ // Power of two because it is a more efficient queue implementation
      _ <- ZStream
             .fromQueue(q, 1) // Until https://github.com/zio/zio/issues/4190 is fixed
             .filterM { case (interrupted, effect @ _) => interrupted.get.map(!_) }
             .throttleShape(max.toLong, interval, max.toLong)(_.size.toLong)
             .mapMParUnordered(Int.MaxValue) { case (interrupted @ _, effect) => effect }
             .runDrain
             .forkManaged
    } yield new RateLimiter {
      override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, E, A] = for {
        start                  <- Promise.make[Nothing, Unit]
        done                   <- Promise.make[Nothing, Unit]
        interruptedRef         <- Ref.make(false)
        action                  = start.succeed(()) *> done.await
        onInterruptOrCompletion = interruptedRef.set(true) *> done.succeed(())
        result                 <- ZManaged
                                    .makeInterruptible_(q.offer((interruptedRef, action)).onInterrupt(onInterruptOrCompletion))(
                                      onInterruptOrCompletion
                                    )
                                    .use_(start.await *> task)
      } yield result
    }
}
