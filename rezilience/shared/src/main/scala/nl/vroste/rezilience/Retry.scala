package nl.vroste.rezilience
import zio.clock.Clock
import zio.duration._
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
  object Schedule {

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

  /**
   * Create a Retry from a ZIO Schedule
   * @param schedule
   * @tparam R
   * @tparam E
   * @return
   */
  def make[R, E](schedule: Schedule[R, E, Any]): ZManaged[Clock with R, Nothing, Retry[E]] =
    ZManaged.environment[Clock with R].map(RetryImpl(_, schedule))

  /**
   * Create a Retry policy with exponential backoff
   *
   * @param min Minimum retry backoff delay
   * @param max Maximum retry backoff delay
   * @param factor Factor with which delays increase
   * @return
   */
  def make(
    min: Duration = 1.second,
    max: Duration = 1.minute,
    factor: Double = 2.0
  ): ZManaged[Clock, Nothing, Retry[Any]] =
    ZManaged.environment[Clock].map(RetryImpl(_, Schedule.exponentialBackoff(min, max, factor)))

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
}
