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

  def histogramFromSettings(latencySettings: HistogramSettings[Long]): IntCountsHistogram = {
    val histOpt: Option[IntCountsHistogram] =
      for {
        min <- latencySettings.min
        max <- latencySettings.max
      } yield {
        val hist = new IntCountsHistogram(
          min,
          max,
          latencySettings.significantDigits
        )
        if (latencySettings.autoResize) hist.setAutoResize(true)
        hist
      }
    histOpt.getOrElse(new IntCountsHistogram(latencySettings.significantDigits))
  }
}
