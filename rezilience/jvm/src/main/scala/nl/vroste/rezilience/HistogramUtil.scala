package nl.vroste.rezilience

import org.HdrHistogram.AbstractHistogram
import org.HdrHistogram.IntCountsHistogram

object HistogramUtil {
  def mergeHistograms[T <: AbstractHistogram](h1: T, h2: T): T =
    (if (h1.getHighestTrackableValue >= h2.getHighestTrackableValue) {
       val newHist = h1.copy()
       newHist.add(h2)
       newHist
     } else {
       val newHist = h2.copy()
       newHist.add(h1)
       newHist
     }).asInstanceOf[T]

  def histogramFromSettings(latencySettings: HistogramSettings[Long]): IntCountsHistogram =
    (latencySettings.min zip latencySettings.max).map { case (min, max) =>
      val hist = new IntCountsHistogram(
        min,
        max,
        latencySettings.significantDigits
      )
      if (latencySettings.autoResize) hist.setAutoResize(true)
      hist
    }.getOrElse(new IntCountsHistogram(latencySettings.significantDigits))
}
