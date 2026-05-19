package it.dewarp.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.dewarp.engine.DewarpParams
import it.dewarp.engine.Engine
import it.dewarp.pdf.PdfReader
import it.dewarp.pdf.PdfWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat

/** Stato di una pagina in modalita' anteprima.
 *  Coordinate (corner e baseline) sono NORMALIZZATE in [0..1] sull'immagine
 *  della pagina sorgente, cosi' restano valide anche se ri-renderiziamo a DPI
 *  diverso per la pipeline finale. */
data class PreviewPage(
    val srcIndex: Int,                                  // indice pagina nel PDF (post auto-rotate)
    val halfIndex: Int,                                 // 0 o 1 (split 2-up), altrimenti 0
    val bitmap: Bitmap,                                 // immagine a DPI ridotto per la UI
    val cropCorners: List<Pair<Float, Float>>,          // 4 corner normalizzati, TL TR BR BL
    val baselines: List<List<Pair<Float, Float>>>,      // polyline normalizzate
)

sealed interface ScreenState {
    data object Idle : ScreenState
    data class Processing(val message: String, val current: Int, val total: Int) : ScreenState
    data class PreviewBuilding(val current: Int, val total: Int) : ScreenState
    data class Preview(
        val pages: List<PreviewPage>,
        val currentIndex: Int,
    ) : ScreenState
    data class Done(val outputUri: Uri, val pagesIn: Int, val pagesOut: Int) : ScreenState
    data class Error(val message: String) : ScreenState
}

class DewarpViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ScreenState>(ScreenState.Idle)
    val state: StateFlow<ScreenState> = _state.asStateFlow()

    private val _params = MutableStateFlow(DewarpParams())
    val params: StateFlow<DewarpParams> = _params.asStateFlow()
    private val _splitTwoUp = MutableStateFlow(true)
    val splitTwoUp: StateFlow<Boolean> = _splitTwoUp.asStateFlow()

    private var job: Job? = null

    init {
        // Carica le native lib OpenCV; senza, le invocazioni Mat fallirebbero.
        OpenCVLoader.initLocal()
    }

    fun setFigureAttenuation(v: Float) {
        _params.value = _params.value.copy(figureAttenuation = v.toDouble())
    }
    fun setMaxDisplacement(v: Float) {
        _params.value = _params.value.copy(maxDisplacementFrac = v.toDouble())
    }
    fun setPolylineSmoothPx(v: Float) {
        // forziamo dispari per il kernel di media mobile
        val px = v.toInt().coerceAtLeast(3).let { if (it % 2 == 0) it + 1 else it }
        _params.value = _params.value.copy(polylineSmoothPx = px)
    }
    fun setSplitTwoUp(v: Boolean) { _splitTwoUp.value = v }
    fun setAutoRotate(v: Boolean) { _params.value = _params.value.copy(autoRotate = v) }
    fun setPolishDegree(d: Int) {
        _params.value = _params.value.copy(polylinePolishDegree = d.coerceIn(0, 3))
    }
    fun setEngine(name: String) {
        if (name == "polyline" || name == "polynomial") {
            _params.value = _params.value.copy(engine = name)
        }
    }
    fun setUseGpu(v: Boolean) {
        _params.value = _params.value.copy(useGpu = v)
        // OpenCV Android non espone Core.setUseOpenCL in 4.10. Lasciamo il flag nei
        // params, l'engine puo' decidere di usare UMat dove utile in futuro.
    }

    private val _previewEnabled = MutableStateFlow(false)
    val previewEnabled: StateFlow<Boolean> = _previewEnabled.asStateFlow()
    fun setPreviewEnabled(v: Boolean) { _previewEnabled.value = v }

    fun startPreview(inputUri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                _state.value = ScreenState.PreviewBuilding(0, 1)
                withContext(Dispatchers.Default) {
                    val pages = buildPreviewPages(inputUri)
                    _state.value = ScreenState.Preview(pages, 0)
                }
            } catch (e: Throwable) {
                _state.value = ScreenState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }

    private fun buildPreviewPages(inputUri: Uri): List<PreviewPage> {
        val ctx = getApplication<Application>()
        val params = _params.value
        val split = _splitTwoUp.value
        val out = ArrayList<PreviewPage>()
        PdfReader(ctx, inputUri).use { reader ->
            val total = reader.pageCount
            for (i in 0 until total) {
                _state.value = ScreenState.PreviewBuilding(i, total)
                // DPI ridotto per la UI di preview (peso bitmap contenuto)
                var src = reader.renderPage(i, 110)
                if (params.autoRotate) {
                    val rot = Engine.autoRotatePage(src)
                    if (rot !== src) { src.release(); src = rot }
                }
                val halves = if (split) Engine.splitTwoUp(src, params) else listOf(src.clone())
                src.release()
                for ((halfIdx, half) in halves.withIndex()) {
                    val w = half.cols().toFloat(); val h = half.rows().toFloat()
                    val quad = Engine.detectPageQuad(half)
                    val cornersNorm = quad.map { (it[0] / w).toFloat() to (it[1] / h).toFloat() }
                    val bls = Engine.extractBaselinesForPreview(half, params)
                    val blsNorm = bls.map { pb ->
                        pb.points.map { (px, py) -> (px / w).toFloat() to (py / h).toFloat() }
                    }
                    val bmp = Bitmap.createBitmap(half.cols(), half.rows(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(half, bmp)
                    out.add(PreviewPage(
                        srcIndex = i, halfIndex = halfIdx, bitmap = bmp,
                        cropCorners = cornersNorm, baselines = blsNorm,
                    ))
                    half.release()
                }
            }
        }
        return out
    }

    fun setPreviewIndex(i: Int) {
        val s = _state.value
        if (s is ScreenState.Preview && i in s.pages.indices) {
            _state.value = s.copy(currentIndex = i)
        }
    }

    fun updateCorner(pageIdx: Int, cornerIdx: Int, x: Float, y: Float) {
        val s = _state.value
        if (s !is ScreenState.Preview) return
        if (pageIdx !in s.pages.indices) return
        val page = s.pages[pageIdx]
        if (cornerIdx !in page.cropCorners.indices) return
        val nx = x.coerceIn(0f, 1f); val ny = y.coerceIn(0f, 1f)
        val updated = page.cropCorners.toMutableList().also { it[cornerIdx] = nx to ny }
        val newPages = s.pages.toMutableList().also { it[pageIdx] = page.copy(cropCorners = updated) }
        _state.value = s.copy(pages = newPages)
    }

    private var recomputeJob: Job? = null

    /** Ricalcola le polyline per la pagina corrente usando i params attuali.
     *  Chiamato dalla UI quando l'utente cambia slider smoothing/polish:
     *  il caller deve fare debouncing per evitare di re-launchare a ogni
     *  pixel di drag dello slider. */
    fun recomputeCurrentPageBaselines() {
        val s = _state.value as? ScreenState.Preview ?: return
        val pageIdx = s.currentIndex
        val page = s.pages.getOrNull(pageIdx) ?: return
        val params = _params.value
        recomputeJob?.cancel()
        recomputeJob = viewModelScope.launch(Dispatchers.Default) {
            val mat = Mat()
            Utils.bitmapToMat(page.bitmap, mat)
            val w = mat.cols().toFloat(); val h = mat.rows().toFloat()
            val bls = try {
                Engine.extractBaselinesForPreview(mat, params)
            } finally {
                mat.release()
            }
            val blsNorm = bls.map { pb ->
                pb.points.map { (px, py) -> (px / w).toFloat() to (py / h).toFloat() }
            }
            // Atomic update: solo se siamo ancora sulla stessa pagina
            val cur = _state.value as? ScreenState.Preview ?: return@launch
            if (cur.currentIndex != pageIdx) return@launch
            val newPages = cur.pages.toMutableList().also {
                it[pageIdx] = it[pageIdx].copy(baselines = blsNorm)
            }
            _state.value = cur.copy(pages = newPages)
        }
    }

    fun resetCornersAuto(pageIdx: Int) {
        val s = _state.value
        if (s !is ScreenState.Preview || pageIdx !in s.pages.indices) return
        val page = s.pages[pageIdx]
        val w = page.bitmap.width.toFloat(); val h = page.bitmap.height.toFloat()
        val mat = Mat()
        Utils.bitmapToMat(page.bitmap, mat)
        val quad = Engine.detectPageQuad(mat)
        mat.release()
        val cornersNorm = quad.map { (it[0] / w).toFloat() to (it[1] / h).toFloat() }
        val newPages = s.pages.toMutableList()
            .also { it[pageIdx] = page.copy(cropCorners = cornersNorm) }
        _state.value = s.copy(pages = newPages)
    }

    fun processFromPreview(inputUri: Uri, outputUri: Uri) {
        val s = _state.value
        if (s !is ScreenState.Preview) return
        val pagesPreview = s.pages
        job?.cancel()
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    runPipelineWithPreview(inputUri, outputUri, pagesPreview)
                }
            } catch (e: Throwable) {
                _state.value = ScreenState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }

    private fun runPipelineWithPreview(inputUri: Uri, outputUri: Uri, preview: List<PreviewPage>) {
        val ctx = getApplication<Application>()
        val params = _params.value
        val split = _splitTwoUp.value
        val total = preview.size
        val outMats = ArrayList<Mat>()
        PdfReader(ctx, inputUri).use { reader ->
            // Raggruppa per srcIndex (per evitare di ri-renderare la stessa pagina sorgente per ogni half)
            val bySrc: Map<Int, List<PreviewPage>> = preview.groupBy { it.srcIndex }
            var done = 0
            for ((srcIdx, group) in bySrc.toSortedMap()) {
                _state.value = ScreenState.Processing("pagina ${done + 1}/$total", done, total)
                var src = reader.renderPage(srcIdx, params.targetDpi)
                if (params.autoRotate) {
                    val rot = Engine.autoRotatePage(src)
                    if (rot !== src) { src.release(); src = rot }
                }
                val halves = if (split) Engine.splitTwoUp(src, params) else listOf(src.clone())
                src.release()
                for ((halfIdx, half) in halves.withIndex()) {
                    val previewMatch = group.firstOrNull { it.halfIndex == halfIdx }
                    val cropped = if (previewMatch != null) {
                        val w = half.cols().toDouble(); val h = half.rows().toDouble()
                        val corners = previewMatch.cropCorners.map {
                            doubleArrayOf((it.first * w).toDouble(), (it.second * h).toDouble())
                        }.toTypedArray()
                        Engine.applyPageCrop(half, corners).also { half.release() }
                    } else half
                    val warped = try { Engine.dewarpPage(cropped, params) } catch (_: Throwable) { cropped.clone() }
                    cropped.release()
                    outMats.add(warped)
                    done++
                    _state.value = ScreenState.Processing("pagina $done/$total", done, total)
                }
            }
            _state.value = ScreenState.Processing("scrittura PDF", total, total)
            PdfWriter(ctx, outputUri, params.targetDpi).use { writer ->
                for (m in outMats) { writer.addPage(m); m.release() }
            }
            _state.value = ScreenState.Done(outputUri, total, outMats.size)
        }
    }

    fun process(inputUri: Uri, outputUri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                _state.value = ScreenState.Processing("rendering", 0, 1)
                withContext(Dispatchers.Default) {
                    runPipeline(inputUri, outputUri)
                }
            } catch (e: Throwable) {
                _state.value = ScreenState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }

    private fun runPipeline(inputUri: Uri, outputUri: Uri) {
        val ctx = getApplication<Application>()
        val params = _params.value
        val split = _splitTwoUp.value
        PdfReader(ctx, inputUri).use { reader ->
            val total = reader.pageCount
            val pages = ArrayList<Mat>()
            for (i in 0 until total) {
                _state.value = ScreenState.Processing("pagina ${i + 1}/$total", i, total)
                var src = reader.renderPage(i, params.targetDpi)
                if (params.autoRotate) {
                    val rotated = Engine.autoRotatePage(src)
                    if (rotated !== src) { src.release(); src = rotated }
                }
                val halves = if (split) Engine.splitTwoUp(src, params) else listOf(src.clone())
                src.release()
                for (h in halves) {
                    val out = try {
                        Engine.dewarpPage(h, params)
                    } catch (e: Throwable) {
                        h.clone()
                    }
                    h.release()
                    pages.add(out)
                }
            }
            _state.value = ScreenState.Processing("scrittura PDF", total, total)
            PdfWriter(ctx, outputUri, params.targetDpi).use { writer ->
                for (p in pages) {
                    writer.addPage(p)
                    p.release()
                }
            }
            _state.value = ScreenState.Done(outputUri, total, pages.size)
        }
    }

    fun reset() { _state.value = ScreenState.Idle }
}
