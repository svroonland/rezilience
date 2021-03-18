package nl.vroste.rezilience

import zio.duration.Duration

case class HistogramSettings(min: Duration, max: Duration, significantDigits: Int = 2)
