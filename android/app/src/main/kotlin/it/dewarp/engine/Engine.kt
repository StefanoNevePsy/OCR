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
        // Bitmap ARGB_8888 -> Utils.bitmapToMat produce un Mat in ordine RGBA,
        // quindi serve COLOR_RGBA2GRAY (non BGRA): COLOR_BGRA2GRAY scambia R<->B
        // e ai pesi luminanza, producendo gray sbagliato sulle figure colorate.
        val code = if (src.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_BGR2GRAY
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

    // ----------------------------- auto-rotate 90 -------------------------- //

    /** Rileva se la pagina e' scansionata a 90 (testo verticale) e ruota in
     *  senso antiorario per riportarla a orientamento corretto. Euristica:
     *  se la varianza del profilo per-colonna domina quella per-riga, la
     *  pagina e' ruotata; altrimenti e' gia' nell'orientamento giusto. */
    fun autoRotatePage(img: Mat): Mat {
        val h = img.rows(); val w = img.cols()
        if (kotlin.math.abs(h - w).toDouble() / kotlin.math.max(h, w) < 0.05) return img.clone()

        val gray = toGray(img)
        val scale = 600.0 / kotlin.math.max(h, w)
        val small = Mat()
        if (scale < 1.0) Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
        else gray.copyTo(small)
        gray.release()
        val bin = binarize(small); small.release()

        val rowSum = Mat(); val colSum = Mat()
        Core.reduce(bin, rowSum, 1, Core.REDUCE_SUM, CvType.CV_64F)
        Core.reduce(bin, colSum, 0, Core.REDUCE_SUM, CvType.CV_64F)
        val bw = bin.cols().toDouble(); val bh = bin.rows().toDouble()
        bin.release()

        fun variance(m: Mat, normalizer: Double): Double {
            val arr = DoubleArray(kotlin.math.max(m.rows(), m.cols()))
            m.get(0, 0, arr)
            for (i in arr.indices) arr[i] /= normalizer
            val mean = arr.average()
            var s = 0.0
            for (v in arr) s += (v - mean) * (v - mean)
            return s / arr.size
        }
        val hVar = variance(rowSum, bw)
        val vVar = variance(colSum, bh)
        rowSum.release(); colSum.release()

        return if (vVar > hVar * 1.3) {
            val out = Mat()
            Core.rotate(img, out, Core.ROTATE_90_COUNTERCLOCKWISE)
            out
        } else img.clone()
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
        val baseScore = rowVariance(binSmall)

        fun scoreAt(angle: Double): Double {
            if (abs(angle) < 1e-3) return baseScore
            val rot = rotate(binSmall, angle, Scalar(0.0))
            val s = rowVariance(rot)
            rot.release()
            return s
        }

        // Coarse-to-fine: passa grossa (~1 deg) poi rifinitura attorno al miglior angolo
        fun search(center: Double, radius: Double, step: Double): Double {
            var bestA = center
            var bestS = scoreAt(center)
            var a = center - radius
            while (a <= center + radius + 1e-6) {
                if (abs(a - center) > 1e-6 && abs(a) >= 1e-3) {
                    val s = scoreAt(a)
                    if (s > bestS) { bestS = s; bestA = a }
                }
                a += step
            }
            return bestA
        }

        val coarseStep = maxOf(1.0, params.deskewStepDeg * 5)
        val coarseA = search(0.0, params.deskewMaxDeg, coarseStep)
        val fineA = search(coarseA, coarseStep, params.deskewStepDeg)
        binSmall.release()

        return if (abs(fineA) < 1e-3) Pair(img.clone(), 0.0)
               else Pair(rotate(img, fineA, whiteScalar(img)), fineA)
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

            // Una sola chiamata JNI per leggere tutto il bounding box.
            val roi = Mat(labels, Rect(x, y, cw, ch))
            val flat = IntArray(cw * ch)
            roi.get(0, 0, flat)
            roi.release()

            val sumsCol = DoubleArray(cw)
            val countsCol = IntArray(cw)
            for (row in 0 until ch) {
                val base = row * cw
                for (col in 0 until cw) {
                    if (flat[base + col] == i) {
                        sumsCol[col] += row
                        countsCol[col]++
                    }
                }
            }
            val ys = DoubleArray(w) { Double.NaN }
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
            // Figure troppo grandi dominano il fit e producono spirali:
            // saltiamo i loro bordi come features (la weight mask le proteggera' comunque).
            if (area > params.figureSkipEdgesAreaFrac * pageArea) continue
            val ratio = cw.toDouble() / max(ch, 1)
            if (ratio > 12 || cw < w * 0.10) continue
            val density = area / (cw.toDouble() * ch)
            if (density < 0.35) continue

            val ysTop = DoubleArray(w) { Double.NaN }
            val ysBot = DoubleArray(w) { Double.NaN }
            val roi = Mat(labels, Rect(x, y, cw, ch))
            val flat = IntArray(cw * ch)
            roi.get(0, 0, flat)
            roi.release()
            // first/last row: per ogni colonna, primo e ultimo y dove label == i
            val firstRow = IntArray(cw) { -1 }
            val lastRow = IntArray(cw) { -1 }
            for (row in 0 until ch) {
                val base = row * cw
                for (col in 0 until cw) {
                    if (flat[base + col] == i) {
                        if (firstRow[col] < 0) firstRow[col] = row
                        lastRow[col] = row
                    }
                }
            }
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
        // weight[c] = 1 - factor * weight[c]  (factor = 1 - figureAttenuation).
        // Loop manuale: evita le ops Core.multiply/add con Scalar che hanno semantica
        // ambigua tra le versioni dei binding (a volte trattano lo Scalar come vettore
        // a 4 canali e per Mat single-channel non broadcastano come atteso).
        val factor = (1.0 - params.figureAttenuation).toFloat()
        val row = FloatArray(w)
        for (y in 0 until h) {
            weight.get(y, 0, row)
            for (c in 0 until w) row[c] = 1f - factor * row[c]
            weight.put(y, 0, row)
        }
        return weight
    }

    // --------------------------- motore polyline --------------------------- //

    private fun extractBaselines(textMask: Mat, binary: Mat, params: DewarpParams): List<DoubleArray> {
        val h = textMask.rows(); val w = textMask.cols()
        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val num = Imgproc.connectedComponentsWithStats(textMask, labels, stats, centroids, 8)

        val collected = ArrayList<Pair<Double, DoubleArray>>()
        for (i in 1 until num) {
            val x = stats[i, Imgproc.CC_STAT_LEFT][0].toInt()
            val y = stats[i, Imgproc.CC_STAT_TOP][0].toInt()
            val cw = stats[i, Imgproc.CC_STAT_WIDTH][0].toInt()
            val ch = stats[i, Imgproc.CC_STAT_HEIGHT][0].toInt()
            if (ch < params.minLineHeightPx || ch > h * 0.25) continue
            if (cw < w * params.lineMinWidthFrac) continue

            val labelROI = Mat(labels, Rect(x, y, cw, ch))
            val binROI = Mat(binary, Rect(x, y, cw, ch))
            val labelFlat = IntArray(cw * ch); labelROI.get(0, 0, labelFlat); labelROI.release()
            val binFlat = ByteArray(cw * ch); binROI.get(0, 0, binFlat); binROI.release()

            // Per ogni colonna: ultima riga con inchiostro (label==i AND bin!=0)
            val lastRow = IntArray(cw) { -1 }
            for (row in 0 until ch) {
                val base = row * cw
                for (col in 0 until cw) {
                    if (labelFlat[base + col] == i && binFlat[base + col].toInt() != 0) {
                        lastRow[col] = row
                    }
                }
            }
            // Filtro is-text-line: contare le transizioni 0<->ink lungo x
            var transitions = 0; var prev = false
            for (col in 0 until cw) {
                val cur = lastRow[col] >= 0
                if (col > 0 && cur != prev) transitions++
                prev = cur
            }
            if (transitions < params.lineMinTransitions) continue

            val ysCol = DoubleArray(cw) { Double.NaN }
            for (col in 0 until cw) {
                if (lastRow[col] >= 0) ysCol[col] = (y + lastRow[col]).toDouble()
            }

            // Filtra descender: scarta colonne dove baseline > median + thresh*ch
            val valid = ysCol.filter { !it.isNaN() }
            if (valid.size < cw * 0.5) continue
            val sorted = valid.sorted()
            val median = sorted[sorted.size / 2]
            val limit = median + params.polylineDescenderThresh * ch
            for (col in 0 until cw) {
                if (!ysCol[col].isNaN() && ysCol[col] > limit) ysCol[col] = Double.NaN
            }

            // Forward/backward fill dei NaN
            var last = Double.NaN
            for (col in 0 until cw) {
                if (ysCol[col].isNaN()) { if (!last.isNaN()) ysCol[col] = last }
                else last = ysCol[col]
            }
            last = Double.NaN
            for (col in cw - 1 downTo 0) {
                if (ysCol[col].isNaN()) { if (!last.isNaN()) ysCol[col] = last }
                else last = ysCol[col]
            }
            if (ysCol.all { it.isNaN() }) continue

            // Pre-filter mediano: rimuove outlier puntuali (punteggiatura, micro-rumore)
            // mantenendo la curvatura macroscopica della baseline.
            val medW = maxOf(3, params.polylineMedianPx or 1)
            if (medW >= 3) {
                val medHalf = medW / 2
                val ysSrc = ysCol.copyOf()
                val window = DoubleArray(medW)
                for (col in 0 until cw) {
                    var n = 0
                    val lo = maxOf(0, col - medHalf); val hi = minOf(cw - 1, col + medHalf)
                    for (s in lo..hi) {
                        if (!ysSrc[s].isNaN()) { window[n++] = ysSrc[s] }
                    }
                    if (n > 0) {
                        val slice = window.copyOf(n); slice.sort()
                        ysCol[col] = slice[n / 2]
                    }
                }
            }

            // Smoothing: media mobile centrata su finestra ampia, per filtrare il
            // jitter lettera-per-lettera mantenendo la curvatura macro della pagina.
            val smW = maxOf(3, params.polylineSmoothPx or 1)
            val half = smW / 2
            val smoothed = DoubleArray(cw)
            for (col in 0 until cw) {
                var sum = 0.0; var cnt = 0
                val lo = maxOf(0, col - half); val hi = minOf(cw - 1, col + half)
                for (s in lo..hi) {
                    if (!ysCol[s].isNaN()) { sum += ysCol[s]; cnt++ }
                }
                smoothed[col] = if (cnt > 0) sum / cnt else Double.NaN
            }

            // Polish polinomiale opzionale: fit di grado basso sulla baseline gia' liscia.
            // Grado 1 (linea retta locale) elimina la wobbliness residua senza overfit.
            val deg = params.polylinePolishDegree
            if (deg > 0 && smoothed.size >= deg + 2) {
                val xc = (cw - 1) / 2.0
                val xStd = kotlin.math.sqrt(((cw * cw - 1) / 12.0).coerceAtLeast(1.0))
                val n = cw
                // Vandermonde normalizzata + Core.solve via SVD
                val V = Mat(n, deg + 1, CvType.CV_64F)
                val y = Mat(n, 1, CvType.CV_64F)
                val rowV = DoubleArray(deg + 1)
                val rowY = DoubleArray(1)
                for (i in 0 until n) {
                    val xn = (i.toDouble() - xc) / xStd
                    var p = 1.0
                    for (j in 0..deg) { rowV[j] = p; p *= xn }
                    V.put(i, 0, *rowV)
                    rowY[0] = if (smoothed[i].isNaN()) 0.0 else smoothed[i]
                    y.put(i, 0, *rowY)
                }
                val c = Mat()
                Core.solve(V, y, c, Core.DECOMP_SVD)
                val coef = DoubleArray(deg + 1)
                val tmp = DoubleArray(1)
                for (j in 0..deg) { c.get(j, 0, tmp); coef[j] = tmp[0] }
                V.release(); y.release(); c.release()
                for (i in 0 until n) {
                    val xn = (i.toDouble() - xc) / xStd
                    var p = 1.0; var s = 0.0
                    for (j in 0..deg) { s += coef[j] * p; p *= xn }
                    smoothed[i] = s
                }
            }

            val ysFull = DoubleArray(w) { Double.NaN }
            for (col in 0 until cw) ysFull[x + col] = smoothed[col]
            val meanY = smoothed.filter { !it.isNaN() }.average()
            collected.add(meanY to ysFull)
        }
        labels.release(); stats.release(); centroids.release()
        return collected.sortedBy { it.first }.map { it.second }
    }

    private fun dewarpWithPolylines(img: Mat, baselines: List<DoubleArray>, h: Int, w: Int, params: DewarpParams): Mat {
        // Estende ai margini con valore al bordo (no slope-based extrapolation)
        val polys = ArrayList<DoubleArray>()
        val targetsList = ArrayList<Double>()
        for (bl in baselines) {
            var firstIdx = -1; var lastIdx = -1
            for (j in 0 until w) {
                if (!bl[j].isNaN()) {
                    if (firstIdx < 0) firstIdx = j
                    lastIdx = j
                }
            }
            if (firstIdx < 0 || lastIdx == firstIdx) continue
            val ext = bl.copyOf()
            val firstV = ext[firstIdx]; val lastV = ext[lastIdx]
            for (j in 0 until firstIdx) ext[j] = firstV
            for (j in lastIdx + 1 until w) ext[j] = lastV
            // Interp lineare per eventuali NaN interni
            for (j in firstIdx..lastIdx) {
                if (ext[j].isNaN()) {
                    var prev = j - 1; while (prev >= 0 && ext[prev].isNaN()) prev--
                    var next = j + 1; while (next < w && ext[next].isNaN()) next++
                    ext[j] = when {
                        prev >= 0 && next < w -> {
                            val t = (j - prev).toDouble() / (next - prev)
                            ext[prev] + t * (ext[next] - ext[prev])
                        }
                        prev >= 0 -> ext[prev]
                        next < w -> ext[next]
                        else -> 0.0
                    }
                }
            }
            polys.add(ext)
            targetsList.add(ext.average())
        }

        if (polys.size < params.minFeaturesForWarp) return img.clone()
        // Ordina per y media
        val pairs = polys.zip(targetsList).sortedBy { it.second }
        val n = pairs.size
        val polysArr = Array(n) { pairs[it].first }
        val targets = DoubleArray(n) { pairs[it].second }
        // Monotonia colonna per colonna
        for (k in 1 until n) {
            val prev = polysArr[k - 1]; val cur = polysArr[k]
            for (c in 0 until w) if (cur[c] < prev[c]) cur[c] = prev[c]
        }

        val maxDispF = (params.maxDisplacementFrac * h).toFloat()
        val mapX = Mat(h, w, CvType.CV_32F)
        run {
            val rowBuf = FloatArray(w) { it.toFloat() }
            for (yy in 0 until h) mapX.put(yy, 0, rowBuf)
        }

        val mapY = Mat(h, w, CvType.CV_32F)
        val rowBuf = FloatArray(w)
        var k = -1
        for (yy in 0 until h) {
            val yd = yy.toDouble()
            while (k + 1 < n && targets[k + 1] <= yd) k++
            if (k < 0) {
                val p0 = polysArr[0]; val t0 = targets[0]
                for (c in 0 until w) {
                    val src = p0[c] + (yd - t0)
                    val d = (src - yd).toFloat()
                    rowBuf[c] = (yd + d.coerceIn(-maxDispF, maxDispF).toDouble()).toFloat()
                }
            } else if (k >= n - 1) {
                val pl = polysArr[n - 1]; val tl = targets[n - 1]
                for (c in 0 until w) {
                    val src = pl[c] + (yd - tl)
                    val d = (src - yd).toFloat()
                    rowBuf[c] = (yd + d.coerceIn(-maxDispF, maxDispF).toDouble()).toFloat()
                }
            } else {
                val tk = targets[k]
                val denom = maxOf(targets[k + 1] - targets[k], 1e-6)
                val t = (yd - tk) / denom
                val pa = polysArr[k]; val pb = polysArr[k + 1]
                for (c in 0 until w) {
                    val src = pa[c] + t * (pb[c] - pa[c])
                    val d = (src - yd).toFloat()
                    rowBuf[c] = (yd + d.coerceIn(-maxDispF, maxDispF).toDouble()).toFloat()
                }
            }
            mapY.put(yy, 0, rowBuf)
        }

        val out = Mat()
        Imgproc.remap(img, out, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, whiteScalar(img))
        mapX.release(); mapY.release()
        return out
    }

    private fun dewarpPagePolyline(img: Mat, params: DewarpParams): Mat {
        val pad = params.padWhite
        val padded = if (pad > 0) {
            val o = Mat()
            Core.copyMakeBorder(img, o, pad, pad, pad, pad, Core.BORDER_CONSTANT, whiteScalar(img))
            o
        } else img.clone()
        val (deskewed, _) = deskew(padded, params)
        padded.release()
        val gray = toGray(deskewed)
        val h = gray.rows(); val w = gray.cols()
        val binary = binarize(gray)
        val textMask = detectTextMask(gray, params)
        val baselines = extractBaselines(textMask, binary, params)
        binary.release(); textMask.release(); gray.release()
        if (baselines.size < params.minFeaturesForWarp) return deskewed
        val out = dewarpWithPolylines(deskewed, baselines, h, w, params)
        deskewed.release()
        return out
    }

    // --------------------------- dewarp principale ------------------------- //

    fun dewarpPage(img: Mat, params: DewarpParams = DewarpParams()): Mat {
        if (params.engine == "polyline") return dewarpPagePolyline(img, params)
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

        // 6. Costruzione vettorizzata di mapY.
        //    Lavoro per riga (compatibile col layout di OpenCV) usando solo FloatArray
        //    e una sola put per riga: H chiamate JNI invece di W.
        val mapX = Mat(h, w, CvType.CV_32F)
        run {
            val rowBuf = FloatArray(w) { it.toFloat() }
            for (yy in 0 until h) mapX.put(yy, 0, rowBuf)
        }

        // polysOrdered[i] (DoubleArray w) -> FloatArray w, con maximum.accumulate per colonna
        val polysMono = Array(nFeats) { i -> FloatArray(w) { j -> polysOrdered[i][j].toFloat() } }
        for (k in 1 until nFeats) {
            val prev = polysMono[k - 1]; val cur = polysMono[k]
            for (c in 0 until w) if (cur[c] < prev[c]) cur[c] = prev[c]
        }

        val slopeTop = FloatArray(w)
        val anchorTop = FloatArray(w)
        val slopeBot = FloatArray(w)
        if (nFeats >= 2) {
            val dtop = max(targets[1] - targets[0], 1.0).toFloat()
            val dbot = max(targets[nFeats - 1] - targets[nFeats - 2], 1.0).toFloat()
            val t0 = targets[0].toFloat()
            for (c in 0 until w) {
                slopeTop[c] = (polysMono[1][c] - polysMono[0][c]) / dtop
                anchorTop[c] = polysMono[0][c] - t0 * slopeTop[c]
                slopeBot[c] = (polysMono[nFeats - 1][c] - polysMono[nFeats - 2][c]) / dbot
            }
        } else {
            for (c in 0 until w) anchorTop[c] = polysMono[0][c]
        }
        val polyLast = polysMono[nFeats - 1]
        val tLast = targets[nFeats - 1].toFloat()

        // 6+7+8 unificati: costruzione mapY + blend con figure weight + clamp,
        // tutto in un singolo passaggio per riga su FloatArray.
        //
        // Perche' un unico loop manuale:
        //  - le Core.* operations con Scalar (min, max, multiply, add) hanno
        //    semantica fragile nei binding Android (trattano lo Scalar come vettore
        //    a 4 canali e per CV_32F single-channel possono non clampare come atteso);
        //  - clampare INLINE durante la costruzione evita di mettere mai valori
        //    fuori range nel Mat finale, anche se il fit polinomiale o le anchor
        //    extrapolation degenerano per pagine con figure molto grandi.
        val maxDispF = (adaptiveMaxDispFrac * h).toFloat()
        val weight = figureWeightMask(gray, params)
        val weightRow = FloatArray(w)
        val rowBuf = FloatArray(w)
        val mapY = Mat(h, w, CvType.CV_32F)

        var k = -1   // indice del segmento per la riga corrente (yy monotono crescente)
        for (yy in 0 until h) {
            val yd = yy.toFloat()
            while (k + 1 < nFeats && targets[k + 1] <= yd) k++

            // Carica la riga della weight mask
            weight.get(yy, 0, weightRow)

            if (k < 0) {
                // sotto il primo target: estrapolazione lineare con anchor_top + slope
                for (c in 0 until w) {
                    val src = anchorTop[c] + yd * slopeTop[c]
                    val blended = weightRow[c] * src + (1f - weightRow[c]) * yd
                    val d = blended - yd
                    rowBuf[c] = yd + if (d > maxDispF) maxDispF else if (d < -maxDispF) -maxDispF else d
                }
            } else if (k >= nFeats - 1) {
                // sopra l'ultimo target
                for (c in 0 until w) {
                    val src = polyLast[c] + (yd - tLast) * slopeBot[c]
                    val blended = weightRow[c] * src + (1f - weightRow[c]) * yd
                    val d = blended - yd
                    rowBuf[c] = yd + if (d > maxDispF) maxDispF else if (d < -maxDispF) -maxDispF else d
                }
            } else {
                // interno: interpolazione lineare tra polysMono[k] e polysMono[k+1]
                val tk = targets[k].toFloat()
                val denom = max(targets[k + 1] - targets[k], 1e-6).toFloat()
                val t = (yd - tk) / denom
                val pa = polysMono[k]; val pb = polysMono[k + 1]
                for (c in 0 until w) {
                    val src = pa[c] + t * (pb[c] - pa[c])
                    val blended = weightRow[c] * src + (1f - weightRow[c]) * yd
                    val d = blended - yd
                    rowBuf[c] = yd + if (d > maxDispF) maxDispF else if (d < -maxDispF) -maxDispF else d
                }
            }
            mapY.put(yy, 0, rowBuf)
        }

        // 9. remap
        val out = Mat()
        Imgproc.remap(
            deskewed, out, mapX, mapY,
            Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, whiteScalar(deskewed),
        )
        gray.release()
        mapX.release(); mapY.release(); weight.release()
        deskewed.release()
        return out
    }

    private fun meanIgnoringNaN(arr: DoubleArray): Double {
        var s = 0.0; var c = 0
        for (v in arr) if (!v.isNaN()) { s += v; c++ }
        return if (c == 0) Double.NaN else s / c
    }

}

