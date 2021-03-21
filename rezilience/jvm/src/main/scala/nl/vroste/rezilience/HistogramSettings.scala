package nl.vroste.rezilience

case class HistogramSettings[T](min: T, max: T, significantDigits: Int = 2)
