package nl.vroste.rezilience

import org.HdrHistogram.AbstractHistogram

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
}
