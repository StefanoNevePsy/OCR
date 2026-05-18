package it.dewarp.engine

import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.max

/**
 * Fit polinomiale 1D di grado `degree` su (xs, ys) con i seguenti accorgimenti:
 *  - normalizza xs per stabilita' numerica
 *  - clamp ai bordi: fuori dal range osservato restituisce il valore al bordo, non l'estrapolazione
 *
 * @param ys array di lunghezza `width`, con NaN per le colonne senza osservazione
 * @return array di lunghezza `width` con la y stimata per ogni colonna
 */
fun polyFitClamped(ys: DoubleArray, degree: Int, width: Int): DoubleArray {
    val out = DoubleArray(width)

    // estrai punti validi
    val xList = ArrayList<Double>()
    val yList = ArrayList<Double>()
    for (i in 0 until width) {
        val v = ys[i]
        if (!v.isNaN()) {
            xList.add(i.toDouble())
            yList.add(v)
        }
    }

    if (xList.size < degree + 2) {
        // fallback: costante = media
        val mean = if (yList.isEmpty()) 0.0 else yList.average()
        for (i in 0 until width) out[i] = mean
        return out
    }

    val n = xList.size
    val xMean = xList.sum() / n
    var xStd = 0.0
    for (v in xList) xStd += (v - xMean) * (v - xMean)
    xStd = max(Math.sqrt(xStd / n), 1.0)

    val xMin = xList.first()
    val xMax = xList.last()

    // Costruisce matrice di Vandermonde V (n x degree+1) con x normalizzato
    val V = Mat(n, degree + 1, CvType.CV_64F)
    val y = Mat(n, 1, CvType.CV_64F)
    val rowV = DoubleArray(degree + 1)
    val rowY = DoubleArray(1)
    for (i in 0 until n) {
        val xn = (xList[i] - xMean) / xStd
        var p = 1.0
        for (j in 0..degree) {
            rowV[j] = p
            p *= xn
        }
        V.put(i, 0, *rowV)
        rowY[0] = yList[i]
        y.put(i, 0, *rowY)
    }

    // Risolve V * c = y in senso minimi quadrati (SVD)
    val c = Mat()
    org.opencv.core.Core.solve(V, y, c, org.opencv.core.Core.DECOMP_SVD or org.opencv.core.Core.DECOMP_NORMAL)

    val coef = DoubleArray(degree + 1)
    val tmp = DoubleArray(1)
    for (j in 0..degree) {
        c.get(j, 0, tmp); coef[j] = tmp[0]
    }
    V.release(); y.release(); c.release()

    fun eval(xRaw: Double): Double {
        val xn = (xRaw - xMean) / xStd
        var p = 1.0
        var s = 0.0
        for (j in 0..degree) { s += coef[j] * p; p *= xn }
        return s
    }

    val valLeft = eval(xMin)
    val valRight = eval(xMax)
    for (i in 0 until width) {
        val xr = i.toDouble()
        out[i] = when {
            xr < xMin -> valLeft
            xr > xMax -> valRight
            else -> eval(xr)
        }
    }
    return out
}
