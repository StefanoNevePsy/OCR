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
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat

sealed interface ScreenState {
    data object Idle : ScreenState
    data class Processing(val message: String, val current: Int, val total: Int) : ScreenState
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
                val src = reader.renderPage(i, params.targetDpi)
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
