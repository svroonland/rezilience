package nl.vroste.rezilience
import zio.clock.Clock
import zio.duration._
import zio.{ Ref, Schedule, ZIO, ZManaged }

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
  def widen[E2](pf: PartialFunction[E2, E]): Retry[E2] = new Retry[E2] {
    override def apply[R, E1 <: E2, A](f: ZIO[R, E1, A]): ZIO[R, E1, A] = for {
      lastError                      <- Ref.make[Option[E1]](None)
      // We lose access to the E2 type error when we convert it using the partial function, so store it in a ref
      inner: ZIO[R, E, Either[E1, A]] =
        f.foldM(
          e2 =>
            lastError.set(Some(e2)) *>
              pf
                .andThen(ZIO.fail(_))
                .lift(e2)
                .getOrElse(ZIO.left(e2)),
          ZIO.right(_)
        )
      result                         <- self.apply(inner).flatMapError(_ => lastError.get).mapError(_.get).absolve
    } yield result
  }

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

  def make[E](schedule: Schedule[Any, E, Any]): ZManaged[Clock, Nothing, Retry[E]] =
    for {
      clock <- ZManaged.environment[Clock]
    } yield new Retry[E] {
      override def apply[R, E1 <: E, A](f: ZIO[R, E1, A]): ZIO[R, E1, A] =
        ZIO.environment[R].flatMap(env => f.provide(env).retry(schedule).provide(clock))
    }
}
