package nl.vroste.rezilience
import zio.clock.Clock
import zio.duration._
import zio._

/**
 * Keeps track of successful calls and failures and determines when the circuit breaker
 * should trip from Closed to Open state
 *
 * Custom implementations are supported
 */
trait TrippingStrategy {
  def onSuccess: UIO[Unit]
  def onFailure: UIO[Unit]
  def shouldTrip: UIO[Boolean]
  def onReset: UIO[Unit]
}

object TrippingStrategy {

  /**
   * For a CircuitBreaker that fails when a number of successive failures (no pun intended) has been counted
   *
   * @param maxFailures Maximum number of failures before tripping the circuit breaker
   * @return
   */
  def failureCount(maxFailures: Int): ZManaged[Any, Nothing, TrippingStrategy] = Ref.make[Int](0).toManaged_.map {
    nrFailedCalls =>
      new TrippingStrategy {
        override def onSuccess: UIO[Unit]     = nrFailedCalls.set(0)
        override def onFailure: UIO[Unit]     = nrFailedCalls.update(_ + 1)
        override def shouldTrip: UIO[Boolean] = nrFailedCalls.get.map(_ == maxFailures)
        override def onReset: UIO[Unit]       = nrFailedCalls.set(0)
      }
  }

  /**
   * For a CircuitBreaker that fails when the fraction of failures in a sample period exceeds some threshold
   *
   * @param failureRateThreshold The minimum fraction (between 0.0 and 1.0) of calls that must fail within the sample duration
   * for the circuit breaker to trip
   * @param sampleDuration Minimum amount of time to record calls
   * @param minThroughput Minimum number of calls required to evaluate the actual failure rate.
   */
  def failureRate(
    failureRateThreshold: Double = 0.5,
    sampleDuration: Duration = 1.minute,
    minThroughput: Int = 10,
    nrSampleBuckets: Int = 10
  ): ZManaged[Clock, Nothing, TrippingStrategy] = {
    require(
      failureRateThreshold > 0.0 && failureRateThreshold < 1.0,
      "failureRateThreshold must be between 0 (exclusive) and 1"
    )

    for {
      samplesRef <- Ref.make[List[Bucket]](List(Bucket.empty)).toManaged_

      // Rotate the buckets periodically
      bucketRotationInterval = sampleDuration * (1.0 / nrSampleBuckets)
      _ <- samplesRef.updateAndGet {
            case Nil                                         => List(Bucket.empty)
            case samples if samples.length < nrSampleBuckets => Bucket.empty +: samples
            case samples                                     => Bucket.empty +: samples.init
          }.delay(bucketRotationInterval)
            .repeat(Schedule.fixed(bucketRotationInterval))
            .forkManaged
    } yield new TrippingStrategy {
      override def onSuccess: UIO[Unit] = updateSamples(true)
      override def onFailure: UIO[Unit] = updateSamples(false)
      override def onReset: UIO[Unit]   = samplesRef.set(List(Bucket.empty))

      def updateSamples(success: Boolean): UIO[Unit] =
        samplesRef.get.flatMap {
          case (bucket @ Bucket(successes, failures)) :: remainingSamples =>
            val updatedBucket =
              if (success) bucket.copy(successes = successes + 1) else bucket.copy(failures = failures + 1)

            val updatedSamples = updatedBucket +: remainingSamples

            samplesRef.set(updatedSamples)
          case Nil =>
            throw new IllegalArgumentException("Samples is supposed to be a NEL")
        }

      override def shouldTrip: UIO[Boolean] = samplesRef.get.map { samples =>
        val total              = samples.foldLeft(0L) { case (acc, Bucket(successes, failures)) => acc + successes + failures }
        val minThroughputMet   = total >= minThroughput
        val minSamplePeriod    = samples.length == nrSampleBuckets
        val currentFailureRate = samples.map(_.failures).sum * 1.0d / samples.map(_.total).sum
        minThroughputMet && minSamplePeriod && (currentFailureRate >= failureRateThreshold)
      }
    }
  }

  private case class Bucket(successes: Long, failures: Long) {
    def total = successes + failures
  }

  private object Bucket {
    val empty = Bucket(0, 0)
  }
}
