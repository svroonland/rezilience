package nl.vroste.rezilience

import zio.clock.Clock
import zio.duration._
import zio._

/**
 * Keeps track of successful calls and failures and determines when the circuit breaker should trip from Closed to Open
 * state
 *
 * Custom implementations are supported
 */
trait TrippingStrategy {

  /**
   * Called for every successful or failed call
   *
   * @param callSuccessful
   * @return
   *   If the CircuitBreaker should trip because of too many failures
   */
  def shouldTrip(callSuccessful: Boolean): UIO[Boolean]

  def onReset: UIO[Unit]
}

object TrippingStrategy {

  /**
   * For a CircuitBreaker that fails when a number of successive failures (no pun intended) has been counted
   *
   * @param maxFailures
   *   Maximum number of failures before tripping the circuit breaker
   * @return
   */
  def failureCount(maxFailures: Int): ZManaged[Any, Nothing, TrippingStrategy] =
    Ref.make[Int](0).toManaged_.map { nrFailedCalls =>
      new TrippingStrategy {
        override def shouldTrip(callSuccessful: Boolean): UIO[Boolean] = if (callSuccessful)
          nrFailedCalls.set(0).as(false)
        else
          nrFailedCalls.modify { case nrFailures =>
            (nrFailures + 1 == maxFailures, nrFailures + 1)
          }
        override def onReset: UIO[Unit]                                = nrFailedCalls.set(0)
      }
    }

  /**
   * For a CircuitBreaker that fails when the fraction of failures in a sample period exceeds some threshold
   *
   * The sample interval is divided into a number of buckets, which are rotated periodically (sampleDuration /
   * nrSampleBuckets) to achieve a moving average of the failure rate.
   *
   * @param failureRateThreshold
   *   The minimum fraction (between 0.0 and 1.0) of calls that must fail within the sample duration for the circuit
   *   breaker to trip
   * @param sampleDuration
   *   Minimum amount of time to record calls
   * @param minThroughput
   *   Minimum number of calls required within the sample period to evaluate the actual failure rate.
   * @param nrSampleBuckets
   *   Nr of intervals to divide
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
    require(nrSampleBuckets > 0, "nrSampleBuckets must be larger than 0")
    require(minThroughput > 0, "minThroughput must be larger than 0")

    for {
      samplesRef <- Ref.make(List(Bucket.empty)).toManaged_

      // Rotate the buckets periodically
      bucketRotationInterval = sampleDuration * (1.0 / nrSampleBuckets)
      _                     <- samplesRef.updateAndGet {
                                 case samples if samples.length < nrSampleBuckets => Bucket.empty +: samples
                                 case samples                                     => Bucket.empty +: samples.init
                               }.repeat(Schedule.spaced(bucketRotationInterval)) // TODO Schedule.fixed when ZIO 1.0.2. is out
                                 .delay(bucketRotationInterval)
                                 .forkManaged
    } yield new TrippingStrategy {
      override def shouldTrip(callSuccessful: Boolean): UIO[Boolean] = samplesRef.modify { case oldSamples =>
        val samples = updateSamples(oldSamples, success = callSuccessful)

        shouldTrip(samples) -> samples
      }
      override def onReset: UIO[Unit]                                = samplesRef.set(List(Bucket.empty))

      private def updateSamples(samples: List[Bucket], success: Boolean): List[Bucket] = samples match {
        case (bucket @ Bucket(successes, failures)) :: remainingSamples =>
          val updatedBucket =
            if (success) bucket.copy(successes = successes + 1) else bucket.copy(failures = failures + 1)

          updatedBucket +: remainingSamples
        case Nil                                                        =>
          throw new IllegalArgumentException("Samples is supposed to be a NEL")
      }

      private def shouldTrip(samples: List[Bucket]): Boolean = {
        val total              = samples.map(_.total).sum
        val minThroughputMet   = total >= minThroughput
        val minSamplePeriod    = samples.length == nrSampleBuckets
        val currentFailureRate = if (total > 0) samples.map(_.failures).sum * 1.0d / total else 0
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
