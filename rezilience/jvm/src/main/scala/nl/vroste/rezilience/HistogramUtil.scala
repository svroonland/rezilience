package nl.vroste.rezilience

import org.HdrHistogram.AbstractHistogram

object HistogramUtil {
  def mergeHistograms[T <: AbstractHistogram](h1: T, h2: T): T = {
    val newHist = h1.copy()
    newHist.add(h2)
    newHist.asInstanceOf[T]
  }

  def addToHistogram[T <: AbstractHistogram](hist: T, values: Seq[Long]): T = {
    val newHist = hist.copy().asInstanceOf[T]
    values.foreach(newHist.recordValue)
    newHist
  }
}
