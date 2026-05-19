package it.dewarp.engine

/**
 * Parametri del pipeline di dewarp. I default replicano `DewarpParams` Python,
 * con margini di sicurezza piu' stretti perche' il porting Java/Kotlin di OpenCV
 * ha precisione e operazioni leggermente diverse.
 */
data class DewarpParams(
    val targetDpi: Int = 300,
    /** "polynomial" (default) o "polyline". Vedi engine.py per i tradeoff. */
    val engine: String = "polynomial",
    val minLineHeightPx: Int = 8,
    val morphKernelW: Int = 41,
    val polyDegree: Int = 2,
    /** Larghezza minima di una riga di testo (frazione di w), motore polyline. */
    val lineMinWidthFrac: Double = 0.18,
    /** Transizioni 0<->255 minime per accettare una riga come testo (motore polyline). */
    val lineMinTransitions: Int = 8,
    /** Finestra media mobile orizzontale sulla baseline (px, motore polyline). */
    val polylineSmoothPx: Int = 81,
    /** Pre-filter mediano (px) prima della media mobile, robusto contro outlier puntuali. */
    val polylineMedianPx: Int = 7,
    /** Polish polinomiale sulla baseline gia' smussata (motore polyline).
     *  0 = polyline pura. 1 = linea retta locale (raccomandato, sweet spot).
     *  Gradi >=2 tendono a oscillare sui residui. */
    val polylinePolishDegree: Int = 1,
    /** Tolleranza descender come frazione dell'altezza riga (motore polyline). */
    val polylineDescenderThresh: Double = 0.35,
    /** Se true, rileva pagine scansionate a 90 e le ruota prima del deskew. */
    val autoRotate: Boolean = true,
    val minFeaturesForWarp: Int = 3,
    val minTextLinesFullConfidence: Int = 6,
    /** Tetto al displacement totale, in frazione dell'altezza pagina. */
    val maxDisplacementFrac: Double = 0.025,
    val houghMinLenFrac: Double = 0.18,
    val houghMaxSlope: Double = 0.12,
    /** Warp residuo applicato sulle aree figura (0 = lasciate intatte). */
    val figureAttenuation: Double = 0.10,
    val figureMinAreaFrac: Double = 0.010,
    /** Figure piu' grandi di questa frazione: NON usare i bordi come features.
     *  Le figure grandi dominano il fit e producono spirali. */
    val figureSkipEdgesAreaFrac: Double = 0.18,
    val gutterSearchFrac: Double = 0.20,
    val gutterMinContrast: Double = 12.0,
    val deskewMaxDeg: Double = 6.0,
    val deskewStepDeg: Double = 0.2,    // allineato al Python
    val padWhite: Int = 20,
    val smoothLinesSigma: Double = 1.5,
    /** Se true, usa OpenCL via T-API (UMat) dove possibile. Sperimentale su Adreno. */
    val useGpu: Boolean = false,
)
