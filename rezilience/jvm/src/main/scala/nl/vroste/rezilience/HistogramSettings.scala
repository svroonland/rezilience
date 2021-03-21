package nl.vroste.rezilience

import zio.duration.Duration

case class HistogramSettings[T](min: T, max: T, significantDigits: Int = 2)
