package it.dewarp.engine

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Port Kotlin/OpenCV dell'engine Python.
 *
 * Pipeline per pagina singola (gia' separata da un eventuale 2-up):
 *   1. Binarizzazione adattiva
 *   2. Deskew globale (rotazione che massimizza la varianza del profilo orizzontale)
 *   3. Rilevamento midline righe via morfologia + connected components
 *   4. Features di supporto (Hough orizzontali + bordi figure) solo se poche righe
 *   5. Fit polinomiale clamped per ogni feature, degree adattivo al numero
 *   6. Campo di displacement interpolato, attenuato su aree figura, clampato in ampiezza
 *   7. Imgproc.remap
 */
object Engine {

    // ----------------------------- utility --------------------------------- //

    private fun toGray(src: Mat): Mat {
        if (src.channels() == 1) return src.clone()
        val gray = Mat()
        val code = if (src.channels() == 4) Imgproc.COLOR_BGRA2GRAY else Imgproc.COLOR_BGR2GRAY
        Imgproc.cvtColor(src, gray, code)
        return gray
    }

    private fun binarize(gray: Mat): Mat {
        val bs = maxOf(31, (gray.rows() / 30) or 1)
        val out = Mat()
        Imgproc.adaptiveThreshold(
            gray, out, 255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV,
            bs, 15.0,
        )
        return out
    }

    /** Solid scalar di sfondo bianco coerente con il numero di canali. */
    private fun whiteScalar(src: Mat): Scalar = when (src.channels()) {
        1 -> Scalar(255.0)
        3 -> Scalar(255.0, 255.0, 255.0)
        4 -> Scalar(255.0, 255.0, 255.0, 255.0)
        else -> Scalar.all(255.0)
    }

    // ----------------------------- split 2-up ------------------------------ //

    fun splitTwoUp(img: Mat, params: DewarpParams = DewarpParams()): List<Mat> {
        val h = img.rows()
        val w = img.cols()
        if (w < h * 1.15) return listOf(img.clone())

        val gray = toGray(img)
        // profilo verticale: media di scurezza per colonna (inverte bianco<->nero)
        val inv = Mat()
        Core.bitwise_not(gray, inv)
        val colMeans = Mat()
        Core.reduce(inv, colMeans, 0, Core.REDUCE_AVG, CvType.CV_32F)
        gray.release(); inv.release()

        val midX = w / 2
        val half = (w * params.gutterSearchFrac / 2).toInt()
        val lo = max(0, midX - half)
        val hi = min(w, midX + half)
        if (hi - lo < 10) { colMeans.release(); return listOf(img.clone()) }

        val region = Mat(colMeans, Rect(lo, 0, hi - lo, 1)).clone()
        val k = max(5, (w / 200) or 1).toDouble()
        Imgproc.GaussianBlur(region, region, Size(k, 1.0), 0.0)

        val flat = FloatArray(region.cols())
        region.get(0, 0, flat)

        val baseline = colMeansToMedian(colMeans)
        colMeans.release(); region.release()

        var peakIdx = 0; var peakVal = -1f; var troughIdx = 0; var troughVal = Float.MAX_VALUE
        for (i in flat.indices) {
            if (flat[i] > peakVal) { peakVal = flat[i]; peakIdx = i }
            if (flat[i] < troughVal) { troughVal = flat[i]; troughIdx = i }
        }
        val splitX = if (peakVal - baseline > baseline - troughVal) lo + peakIdx else lo + troughIdx
        val contrast = max(abs(peakVal - baseline), abs(troughVal - baseline))
        if (contrast < params.gutterMinContrast) return listOf(img.clone())

        val left = Mat(img, Rect(0, 0, splitX, h)).clone()
        val right = Mat(img, Rect(splitX, 0, w - splitX, h)).clone()
        return listOf(left, right)
    }

    private fun colMeansToMedian(colMeans: Mat): Float {
        val arr = FloatArray(colMeans.cols())
        colMeans.get(0, 0, arr)
        val sorted = arr.sortedArray()
        return sorted[sorted.size / 2]
    }

    // ----------------------------- deskew ---------------------------------- //

    private fun rotate(img: Mat, angleDeg: Double, borderValue: Scalar): Mat {
        val h = img.rows(); val w = img.cols()
        val center = Point(w / 2.0, h / 2.0)
        val m = Imgproc.getRotationMatrix2D(center, angleDeg, 1.0)
        val cos = abs(m.get(0, 0)[0])
        val sin = abs(m.get(0, 1)[0])
        val newW = (h * sin + w * cos).toInt()
        val newH = (h * cos + w * sin).toInt()
        m.put(0, 2, m.get(0, 2)[0] + newW / 2.0 - w / 2.0)
        m.put(1, 2, m.get(1, 2)[0] + newH / 2.0 - h / 2.0)
        val out = Mat()
        Imgproc.warpAffine(
            img, out, m, Size(newW.toDouble(), newH.toDouble()),
            Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, borderValue,
        )
        m.release()
        return out
    }

    private fun rowVariance(binary: Mat): Double {
        val rowSum = Mat()
        Core.reduce(binary, rowSum, 1, Core.REDUCE_SUM, CvType.CV_64F)
        val mean = org.opencv.core.MatOfDouble()
        val stddev = org.opencv.core.MatOfDouble()
        Core.meanStdDev(rowSum, mean, stddev)
        val s = stddev[0, 0][0]
        rowSum.release(); mean.release(); stddev.release()
        return s * s
    }

    private fun deskew(img: Mat, params: DewarpParams): Pair<Mat, Double> {
        val gray = toGray(img)
        val scale = 800.0 / max(gray.cols(), gray.rows())
        val small = Mat()
        Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
        gray.release()
        val binSmall = binarize(small); small.release()

        var bestAngle = 0.0
        var bestScore = rowVariance(binSmall)
        val step = params.deskewStepDeg
        val limit = params.deskewMaxDeg
        var angle = -limit
        while (angle <= limit + 1e-6) {
            if (abs(angle) >= 1e-3) {
                val rot = rotate(binSmall, angle, Scalar(0.0))
                val s = rowVariance(rot)
                rot.release()
                if (s > bestScore) { bestScore = s; bestAngle = angle }
            }
            angle += step
        }
        binSmall.release()

        return if (abs(bestAngle) < 1e-3) Pair(img.clone(), 0.0)
               else Pair(rotate(img, bestAngle, whiteScalar(img)), bestAngle)
    }

    // ----------------------------- line tracing ---------------------------- //

    private fun detectTextMask(gray: Mat, params: DewarpParams): Mat {
        val binary = binarize(gray)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(params.morphKernelW.toDouble(), 3.0))
        val closed = Mat()
        Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, k)
        val k2 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.morphologyEx(closed, closed, Imgproc.MORPH_OPEN, k2)
        binary.release(); k.release(); k2.release()
        return closed
    }

    private fun traceLines(textMask: Mat, params: DewarpParams): List<DoubleArray> {
        val h = textMask.rows(); val w = textMask.cols()
        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val num = Imgproc.connectedComponentsWithStats(textMask, labels, stats, centroids, 8)

        val result = ArrayList<Pair<Double, DoubleArray>>()
        for (i in 1 until num) {
            val x = stats[i, Imgproc.CC_STAT_LEFT][0].toInt()
            val y = stats[i, Imgproc.CC_STAT_TOP][0].toInt()
            val cw = stats[i, Imgproc.CC_STAT_WIDTH][0].toInt()
            val ch = stats[i, Imgproc.CC_STAT_HEIGHT][0].toInt()
            if (ch < params.minLineHeightPx) continue
            if (cw < w * 0.10) continue
            if (ch > h * 0.25) continue

            // Per ogni colonna del bounding box, calcola y media dei pixel attivi
            val ys = DoubleArray(w) { Double.NaN }
            val roi = Mat(labels, Rect(x, y, cw, ch))
            val sumsCol = DoubleArray(cw)
            val countsCol = IntArray(cw)
            val buf = IntArray(cw)
            for (row in 0 until ch) {
                roi.row(row).get(0, 0, buf)
                for (col in 0 until cw) {
                    if (buf[col] == i) {
                        sumsCol[col] += row
                        countsCol[col]++
                    }
                }
            }
            roi.release()
            var meanY = 0.0; var cnt = 0
            for (col in 0 until cw) {
                if (countsCol[col] > 0) {
                    val m = y + sumsCol[col] / countsCol[col]
                    ys[x + col] = m
                    meanY += m; cnt++
                }
            }
            if (cnt > 0) result.add((meanY / cnt) to ys)
        }
        labels.release(); stats.release(); centroids.release()

        return result.sortedBy { it.first }.map { it.second }
    }

    // --------------------------- support features -------------------------- //

    private fun houghHorizontalFeatures(gray: Mat, params: DewarpParams): List<DoubleArray> {
        val w = gray.cols()
        val edges = Mat()
        Imgproc.Canny(gray, edges, 60.0, 160.0)
        val minLen = max(40, (w * params.houghMinLenFrac).toInt())
        val lines = Mat()
        Imgproc.HoughLinesP(edges, lines, 1.0, PI / 180.0, 80, minLen.toDouble(), 15.0)
        edges.release()
        val out = ArrayList<DoubleArray>()
        val buf = IntArray(4)
        for (i in 0 until lines.rows()) {
            lines.get(i, 0, buf)
            var x1 = buf[0]; var y1 = buf[1]; var x2 = buf[2]; var y2 = buf[3]
            if (x2 < x1) { val t = x1; x1 = x2; x2 = t; val u = y1; y1 = y2; y2 = u }
            val dx = x2 - x1
            if (dx < minLen) continue
            val slope = (y2 - y1).toDouble() / dx
            if (abs(slope) > params.houghMaxSlope) continue
            val ys = DoubleArray(w) { Double.NaN }
            for (xi in x1..x2) ys[xi] = y1 + slope * (xi - x1)
            out.add(ys)
        }
        lines.release()
        return out
    }

    private fun figureHorizontalEdges(gray: Mat, params: DewarpParams): List<DoubleArray> {
        val h = gray.rows(); val w = gray.cols()
        val binary = binarize(gray)
        val kBig = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(35.0, 35.0))
        val blob = Mat()
        Imgproc.morphologyEx(binary, blob, Imgproc.MORPH_CLOSE, kBig)
        binary.release(); kBig.release()

        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val num = Imgproc.connectedComponentsWithStats(blob, labels, stats, centroids, 8)
        blob.release()

        val pageArea = h.toLong() * w.toLong()
        val feats = ArrayList<DoubleArray>()
        for (i in 1 until num) {
            val x = stats[i, Imgproc.CC_STAT_LEFT][0].toInt()
            val y = stats[i, Imgproc.CC_STAT_TOP][0].toInt()
            val cw = stats[i, Imgproc.CC_STAT_WIDTH][0].toInt()
            val ch = stats[i, Imgproc.CC_STAT_HEIGHT][0].toInt()
            val area = stats[i, Imgproc.CC_STAT_AREA][0]
            if (area < params.figureMinAreaFrac * pageArea) continue
            val ratio = cw.toDouble() / max(ch, 1)
            if (ratio > 12 || cw < w * 0.10) continue
            val density = area / (cw.toDouble() * ch)
            if (density < 0.35) continue

            val ysTop = DoubleArray(w) { Double.NaN }
            val ysBot = DoubleArray(w) { Double.NaN }
            val roi = Mat(labels, Rect(x, y, cw, ch))
            val buf = IntArray(cw)
            // first row: per ogni colonna, primo y dove label == i
            val firstRow = IntArray(cw) { -1 }
            val lastRow = IntArray(cw) { -1 }
            for (row in 0 until ch) {
                roi.row(row).get(0, 0, buf)
                for (col in 0 until cw) {
                    if (buf[col] == i) {
                        if (firstRow[col] < 0) firstRow[col] = row
                        lastRow[col] = row
                    }
                }
            }
            roi.release()
            for (col in 0 until cw) {
                if (firstRow[col] >= 0) {
                    ysTop[x + col] = (y + firstRow[col]).toDouble()
                    ysBot[x + col] = (y + lastRow[col]).toDouble()
                }
            }
            feats.add(ysTop); feats.add(ysBot)
        }
        labels.release(); stats.release(); centroids.release()
        return feats
    }

    private fun figureWeightMask(gray: Mat, params: DewarpParams): Mat {
        val h = gray.rows(); val w = gray.cols()
        val binary = binarize(gray)
        val kBig = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(35.0, 35.0))
        val blob = Mat()
        Imgproc.morphologyEx(binary, blob, Imgproc.MORPH_CLOSE, kBig)
        binary.release(); kBig.release()

        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val num = Imgproc.connectedComponentsWithStats(blob, labels, stats, centroids, 8)
        blob.release()

        val mask = Mat.zeros(h, w, CvType.CV_8U)
        val pageArea = h.toLong() * w.toLong()
        for (i in 1 until num) {
            val x = stats[i, Imgproc.CC_STAT_LEFT][0].toInt()
            val y = stats[i, Imgproc.CC_STAT_TOP][0].toInt()
            val cw = stats[i, Imgproc.CC_STAT_WIDTH][0].toInt()
            val ch = stats[i, Imgproc.CC_STAT_HEIGHT][0].toInt()
            val area = stats[i, Imgproc.CC_STAT_AREA][0]
            if (area < params.figureMinAreaFrac * pageArea) continue
            val ratio = cw.toDouble() / max(ch, 1)
            if (ratio > 12) continue
            val density = area / (cw.toDouble() * ch)
            if (density < 0.35) continue
            Imgproc.rectangle(mask, Point(x.toDouble(), y.toDouble()),
                Point((x + cw).toDouble(), (y + ch).toDouble()), Scalar(255.0), -1)
        }
        labels.release(); stats.release(); centroids.release()
        Imgproc.GaussianBlur(mask, mask, Size(61.0, 61.0), 0.0)

        val weight = Mat()
        mask.convertTo(weight, CvType.CV_32F, 1.0 / 255.0)
        mask.release()
        // weight = 1 - mask * (1 - figureAttenuation)
        Core.multiply(weight, Scalar(-(1.0 - params.figureAttenuation)), weight)
        Core.add(weight, Scalar(1.0), weight)
        return weight
    }

    // --------------------------- dewarp principale ------------------------- //

    fun dewarpPage(img: Mat, params: DewarpParams = DewarpParams()): Mat {
        // 0. pad bianco
        val pad = params.padWhite
        val padded = if (pad > 0) {
            val o = Mat()
            Core.copyMakeBorder(img, o, pad, pad, pad, pad, Core.BORDER_CONSTANT, whiteScalar(img))
            o
        } else img.clone()

        // 1. deskew
        val (deskewed, _) = deskew(padded, params)
        padded.release()

        val gray = toGray(deskewed)
        val h = gray.rows(); val w = gray.cols()

        // 2. text lines
        val textMask = detectTextMask(gray, params)
        val textLines = traceLines(textMask, params)
        textMask.release()
        val nText = textLines.size

        // 3. features: integra support solo se poche righe
        val features = ArrayList<DoubleArray>(textLines)
        if (nText < params.minTextLinesFullConfidence) {
            val supportH = houghHorizontalFeatures(gray, params)
            val supportF = figureHorizontalEdges(gray, params)
            val existingMeans = ArrayList<Double>().apply {
                for (f in features) {
                    val m = meanIgnoringNaN(f)
                    if (!m.isNaN()) add(m)
                }
            }
            val dedupTol = max(params.minLineHeightPx * 1.5, 12.0)
            for (cand in supportH + supportF) {
                val cm = meanIgnoringNaN(cand)
                if (cm.isNaN()) continue
                if (existingMeans.any { abs(cm - it) < dedupTol }) continue
                features.add(cand)
                existingMeans.add(cm)
            }
        }
        val nFeats = features.size

        if (nFeats < params.minFeaturesForWarp) {
            gray.release()
            return deskewed
        }

        // 4. degree e displacement adattivi
        val adaptiveDegree = when {
            nFeats < 5 -> 1
            nFeats < 9 -> min(2, params.polyDegree)
            else -> params.polyDegree
        }
        val textConf = min(1.0, nText.toDouble() / max(params.minTextLinesFullConfidence, 1))
        val featConf = min(1.0, nFeats / 12.0)
        val confidence = 0.35 + 0.65 * (0.6 * textConf + 0.4 * featConf)
        val adaptiveMaxDispFrac = params.maxDisplacementFrac * confidence

        // 5. fit polinomi
        val polys = Array(nFeats) { polyFitClamped(features[it], adaptiveDegree, w) }
        // ordina per y media
        val polyMeans = DoubleArray(nFeats) { polys[it].average() }
        val order = (0 until nFeats).sortedBy { polyMeans[it] }
        val polysOrdered = Array(nFeats) { polys[order[it]] }
        val targets = DoubleArray(nFeats) { polysOrdered[it].average() }

        // 6. campo di displacement: per ogni colonna, interpola da targets a src_ys (con monotonicita')
        val mapY = Mat(h, w, CvType.CV_32F)
        val mapX = Mat(h, w, CvType.CV_32F)
        val mapYRow = FloatArray(w)
        val mapXRow = FloatArray(w)
        for (yy in 0 until h) {
            for (xx in 0 until w) mapXRow[xx] = xx.toFloat()
            mapX.put(yy, 0, mapXRow)
        }

        val srcY = DoubleArray(nFeats)
        val xp = DoubleArray(nFeats + 2)
        val fp = DoubleArray(nFeats + 2)
        for (x in 0 until w) {
            for (k in 0 until nFeats) srcY[k] = polysOrdered[k][x]
            // enforce monotonia
            for (k in 1 until nFeats) if (srcY[k] < srcY[k - 1]) srcY[k] = srcY[k - 1]
            // ancore ai bordi: estrapolazione lineare con la prima/ultima pendenza
            val anchorTop = if (nFeats >= 2) {
                val slope = (srcY[1] - srcY[0]) / max(targets[1] - targets[0], 1.0)
                srcY[0] - targets[0] * slope
            } else srcY[0]
            val anchorBot = if (nFeats >= 2) {
                val slope = (srcY[nFeats - 1] - srcY[nFeats - 2]) / max(targets[nFeats - 1] - targets[nFeats - 2], 1.0)
                srcY[nFeats - 1] + (h - 1 - targets[nFeats - 1]) * slope
            } else srcY[nFeats - 1]
            xp[0] = 0.0; fp[0] = anchorTop
            for (k in 0 until nFeats) { xp[k + 1] = targets[k]; fp[k + 1] = srcY[k] }
            xp[nFeats + 1] = (h - 1).toDouble(); fp[nFeats + 1] = anchorBot
            // ordina xp
            val idx = (0 until nFeats + 2).sortedBy { xp[it] }
            val xpS = DoubleArray(nFeats + 2) { xp[idx[it]] }
            val fpS = DoubleArray(nFeats + 2) { fp[idx[it]] }
            // interp lineare per y_dst in 0..h-1
            for (y in 0 until h) {
                mapYRow[y] = linInterp(y.toDouble(), xpS, fpS).toFloat()
            }
            mapY.put(0, x, mapYRow)
        }

        // 7. attenuazione su figure
        val weight = figureWeightMask(gray, params)
        val mapYW = Mat(h, w, CvType.CV_32F)
        // mapY_w = weight * mapY + (1 - weight) * identity_y
        val identity = Mat(h, w, CvType.CV_32F)
        for (yy in 0 until h) {
            val row = FloatArray(w) { yy.toFloat() }
            identity.put(yy, 0, row)
        }
        // oneMinusW = 1 - weight (su Mat float32). OpenCV Java non offre Scalar - Mat,
        // costruiamo il complemento via (-1 * weight) + 1.
        val oneMinusW = Mat()
        Core.multiply(weight, Scalar(-1.0), oneMinusW)
        Core.add(oneMinusW, Scalar(1.0), oneMinusW)
        val a = Mat(); Core.multiply(mapY, weight, a)
        val b = Mat(); Core.multiply(identity, oneMinusW, b)
        Core.add(a, b, mapYW)
        a.release(); b.release(); oneMinusW.release()

        // 8. clamp displacement
        val maxDisp = adaptiveMaxDispFrac * h
        val delta = Mat()
        Core.subtract(mapYW, identity, delta)
        val deltaMin = Mat(); val deltaMax = Mat()
        Core.min(delta, Scalar(maxDisp), deltaMax)
        Core.max(deltaMax, Scalar(-maxDisp), deltaMin)
        Core.add(identity, deltaMin, mapYW)
        delta.release(); deltaMin.release(); deltaMax.release(); identity.release()

        // 9. remap
        val out = Mat()
        Imgproc.remap(
            deskewed, out, mapX, mapYW,
            Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, whiteScalar(deskewed),
        )
        gray.release()
        mapX.release(); mapY.release(); mapYW.release(); weight.release()
        deskewed.release()
        return out
    }

    private fun meanIgnoringNaN(arr: DoubleArray): Double {
        var s = 0.0; var c = 0
        for (v in arr) if (!v.isNaN()) { s += v; c++ }
        return if (c == 0) Double.NaN else s / c
    }

    private fun linInterp(x: Double, xp: DoubleArray, fp: DoubleArray): Double {
        if (x <= xp[0]) return fp[0]
        if (x >= xp.last()) return fp.last()
        // binary search
        var lo = 0; var hi = xp.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xp[mid] <= x) lo = mid else hi = mid
        }
        val t = (x - xp[lo]) / (xp[hi] - xp[lo])
        return fp[lo] + t * (fp[hi] - fp[lo])
    }
}

