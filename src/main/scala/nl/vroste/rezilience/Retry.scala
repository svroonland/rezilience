package nl.vroste.rezilience
import zio.Schedule
import zio.clock.Clock
import zio.duration._

object Retry {

  /**
   * Schedule for exponential backoff up to a maximum interval and an optional maximum number of retries
   *
   * @param min Minimum backoff time
   * @param max Maximum backoff time. When this value is reached, subsequent intervals will be equal to this value.
   * @param factor Exponential factor. 2 means doubling, 1 is constant, < 1 means decreasing
   * @param maxRecurs Maximum retries. When this number is exceeded, the schedule will end
   * @tparam A Schedule input
   */
  def exponentialBackoff[A](
    min: Duration,
    max: Duration,
    factor: Double = 2.0,
    maxRecurs: Option[Int] = None
  ): Schedule[Clock, A, (Duration, Long)] =
    (Schedule.exponential(min, factor).whileOutput(_ <= max) andThen Schedule.fixed(max).as(max)) &&
      maxRecurs.map(Schedule.recurs).getOrElse(Schedule.forever)

  /**
   * Apply the given schedule only when inputs match the partial function
   */
  def when[Env, In, Out](pf: PartialFunction[In, Any])(schedule: Schedule[Env, In, Out]): Schedule[Env, In, (In, Out)] =
    Schedule.recurWhile(pf.isDefinedAt) && schedule

}
