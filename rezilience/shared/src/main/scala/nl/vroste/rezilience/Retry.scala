package nl.vroste.rezilience
import zio.clock.Clock
import zio.duration._
import zio.random.Random
import zio.{ Schedule, ZIO, ZManaged }

trait Retry[-E] { self =>
  def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, E1, A]

  /**
   * Transform this policy to apply to larger class of errors
   *
   * Only where the partial function is defined will the policy be applied, other errors are not retried
   *
   * @param pf
   * @tparam E2
   * @return
   */
  def widen[E2](pf: PartialFunction[E2, E]): Retry[E2]

  def toPolicy: Policy[E] = new Policy[E] {
    override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, Policy.PolicyError[E1], A] =
      self(f).mapError(Policy.WrappedError(_))
  }
}

object Retry {

  /**
   * Create a Retry policy with a common retry schedule
   *
   * By default the first retry is done immediately. With transient / random failures this method gives the
   * highest chance of fast success.
   * After that Retry uses exponential backoff between some minimum and maximum duration. Jitter is added
   * to prevent spikes of retries.
   * An optional maximum number of retries ensures that retrying does not continue forever.
   *
   * @param min Minimum retry backoff delay
   * @param max Maximum backoff time. When this value is reached, subsequent intervals will be equal to this value.
   * @param factor Factor with which delays increase
   * @param retryImmediately Retry immediately after the first failure
   * @param maxRetries Maximum number of retries
   */
  def make(
    min: Duration = 1.second,
    max: Duration = 1.minute,
    factor: Double = 2.0,
    retryImmediately: Boolean = true,
    maxRetries: Option[Int] = Some(3)
  ): ZManaged[Clock with Random, Nothing, Retry[Any]] =
    make(Schedules.common(min, max, factor, retryImmediately, maxRetries))

  /**
   * Create a Retry from a ZIO Schedule
   */
  def make[R, E](schedule: Schedule[R, E, Any]): ZManaged[Clock with R, Nothing, Retry[E]] =
    ZManaged.environment[Clock with R].map(RetryImpl(_, schedule))

  private case class RetryImpl[-E, ScheduleEnv](
    scheduleEnv: Clock with ScheduleEnv,
    schedule: Schedule[ScheduleEnv, E, Any]
  ) extends Retry[E] {
    override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, E1, A] =
      ZIO.environment[R].flatMap(env => f.provide(env).retry(schedule).provide(scheduleEnv))

    override def widen[E2](pf: PartialFunction[E2, E]): Retry[E2] = RetryImpl[E2, ScheduleEnv](
      scheduleEnv,
      (zio.Schedule.stop ||| schedule).contramap[ScheduleEnv, E2] { e2 =>
        pf.andThen(Right.apply[E2, E](_)).applyOrElse(e2, Left.apply[E2, E](_))
      }
    )
  }

  /**
   * Convenience methods to create common ZIO schedules for retrying
   */
  object Schedules {

    /**
     * A common-practice schedule for retrying
     *
     * By default the first retry is done immediately. With transient / random failures this method gives the
     * highest chance of fast success.
     * After that Retry uses exponential backoff between some minimum and maximum duration. Jitter is added
     * to prevent spikes of retries.
     * An optional maximum number of retries ensures that retrying does not continue forever.
     *
     * See also https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
     *
     * @param min Minimum retry backoff delay
     * @param max Maximum backoff time. When this value is reached, subsequent intervals will be equal to this value.
     * @param factor Factor with which delays increase
     * @param retryImmediately Retry immediately after the first failure
     * @param maxRetries Maximum number of retries
     */
    def common(
      min: Duration = 1.second,
      max: Duration = 1.minute,
      factor: Double = 2.0,
      retryImmediately: Boolean = true,
      maxRetries: Option[Int] = Some(3)
    ): Schedule[Any with Random, Any, (Any, Long)] =
      ((if (retryImmediately) zio.Schedule.once else zio.Schedule.stop) andThen
        exponentialBackoff(min, max, factor).jittered) &&
        maxRetries.fold(zio.Schedule.forever)(zio.Schedule.recurs)

    /**
     * Schedule for exponential backoff up to a maximum interval
     *
     * @param min Minimum backoff time
     * @param max Maximum backoff time. When this value is reached, subsequent intervals will be equal to this value.
     * @param factor Exponential factor. 2 means doubling, 1 is constant, < 1 means decreasing
     * @tparam E Schedule input
     */
    def exponentialBackoff[E](
      min: Duration,
      max: Duration,
      factor: Double = 2.0
    ): Schedule[Any, E, Duration] =
      zio.Schedule.exponential(min, factor).whileOutput(_ <= max) andThen zio.Schedule.fixed(max).as(max)

    /**
     * Apply the given schedule only when inputs match the partial function
     */
    def whenCase[Env, In, Out](pf: PartialFunction[In, Any])(
      schedule: Schedule[Env, In, Out]
    ): Schedule[Env, In, (In, Out)] =
      zio.Schedule.recurWhile(pf.isDefinedAt) && schedule
  }
}
