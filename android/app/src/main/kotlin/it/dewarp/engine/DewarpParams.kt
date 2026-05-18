package it.dewarp.engine

/**
 * Parametri del pipeline di dewarp. I default replicano `DewarpParams` Python.
 */
data class DewarpParams(
    val targetDpi: Int = 300,
    val minLineHeightPx: Int = 8,
    val morphKernelW: Int = 41,
    val polyDegree: Int = 2,
    val minFeaturesForWarp: Int = 3,
    val minTextLinesFullConfidence: Int = 6,
    val maxDisplacementFrac: Double = 0.04,
    val houghMinLenFrac: Double = 0.18,
    val houghMaxSlope: Double = 0.12,
    val figureAttenuation: Double = 0.15,
    val figureMinAreaFrac: Double = 0.010,
    val gutterSearchFrac: Double = 0.20,
    val gutterMinContrast: Double = 12.0,
    val deskewMaxDeg: Double = 6.0,
    val deskewStepDeg: Double = 0.4,        // step piu' largo del Python: meno tentativi su mobile
    val padWhite: Int = 20,
    val smoothLinesSigma: Double = 1.5,
    /** Se true, usa OpenCL via T-API (UMat) dove possibile. Sperimentale su Adreno. */
    val useGpu: Boolean = false,
)
