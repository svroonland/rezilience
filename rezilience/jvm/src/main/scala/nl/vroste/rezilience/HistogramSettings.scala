package nl.vroste.rezilience

/**
 * Settings for histogram metric
 *
 * Also see `org.HdrHistogram.IntCountsHistogram`
 *
 * @param min
 *   Minimum discernible value
 * @param max
 *   Maximum discernible value
 * @param significantDigits
 *   Specifies the precision to use. This is the number of significant decimal digits to which the histogram will
 *   maintain value resolution and separation. Must be a non-negative integer between 0 and 5.
 * @param autoResize
 *   Automatically resize the histogram when attempting to register a value larger than `max`. Defaults or true when min
 *   or max is not given.
 */
case class HistogramSettings[T](
  min: Option[T] = None,
  max: Option[T] = None,
  significantDigits: Int = 2,
  autoResize: Boolean = true
)

object HistogramSettings {
  def default[T]: HistogramSettings[T] = HistogramSettings()
}
