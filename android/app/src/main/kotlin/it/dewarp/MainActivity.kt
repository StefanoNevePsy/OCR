package it.dewarp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.dewarp.viewmodel.PreviewPage
import androidx.lifecycle.viewmodel.compose.viewModel
import it.dewarp.ui.theme.DewarpTheme
import it.dewarp.viewmodel.DewarpViewModel
import it.dewarp.viewmodel.ScreenState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intentUri = intentPdfUri(intent)
        setContent {
            DewarpTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DewarpApp(initialUri = intentUri)
                }
            }
        }
    }

    private fun intentPdfUri(i: Intent?): Uri? {
        if (i == null) return null
        if (i.action == Intent.ACTION_VIEW) i.data?.let { return it }
        // SEND / SEND_MULTIPLE: prendi la prima Uri condivisa
        @Suppress("DEPRECATION")
        val single = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (single != null) return single
        @Suppress("DEPRECATION")
        val multiple = i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        return multiple?.firstOrNull() ?: i.data
    }
}

@Composable
fun DewarpApp(initialUri: Uri?, vm: DewarpViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val params by vm.params.collectAsState()
    val splitTwoUp by vm.splitTwoUp.collectAsState()
    val previewEnabled by vm.previewEnabled.collectAsState()
    var pickedUri by remember { mutableStateOf<Uri?>(initialUri) }

    val openPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pickedUri = uri
    }
    val createPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val input = pickedUri
        if (uri != null && input != null) {
            if (state is ScreenState.Preview) vm.processFromPreview(input, uri)
            else vm.process(input, uri)
        }
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header()

        when (val s = state) {
            is ScreenState.Idle -> IdleStage(
                pickedUri = pickedUri,
                params = params,
                splitTwoUp = splitTwoUp,
                previewEnabled = previewEnabled,
                onPick = { openPdf.launch(arrayOf("application/pdf")) },
                onFigureAtt = { vm.setFigureAttenuation(it) },
                onMaxDisp = { vm.setMaxDisplacement(it) },
                onSmoothPx = { vm.setPolylineSmoothPx(it) },
                onPolishDegree = { vm.setPolishDegree(it) },
                onSplitToggle = { vm.setSplitTwoUp(it) },
                onAutoRotateToggle = { vm.setAutoRotate(it) },
                onPreviewToggle = { vm.setPreviewEnabled(it) },
                onGpuToggle = { vm.setUseGpu(it) },
                onEngineChange = { vm.setEngine(it) },
                onStart = {
                    val input = pickedUri
                    if (input != null) {
                        if (previewEnabled) vm.startPreview(input)
                        else createPdf.launch("dewarped.pdf")
                    }
                },
            )
            is ScreenState.PreviewBuilding -> PreviewBuildingStage(s)
            is ScreenState.Preview -> PreviewStage(
                state = s,
                params = params,
                onIndexChange = { vm.setPreviewIndex(it) },
                onCornerDrag = { idx, x, y -> vm.updateCorner(s.currentIndex, idx, x, y) },
                onResetCorners = { vm.resetCornersAuto(s.currentIndex) },
                onSmoothPx = { vm.setPolylineSmoothPx(it) },
                onPolishDegree = { vm.setPolishDegree(it) },
                onEngineChange = { vm.setEngine(it) },
                onParamsChanged = { vm.recomputeCurrentPageBaselines() },
                onConfirm = { createPdf.launch("dewarped.pdf") },
                onCancel = { vm.reset() },
            )
            is ScreenState.Processing -> ProcessingStage(s)
            is ScreenState.Done -> DoneStage(s, onReset = {
                pickedUri = null
                vm.reset()
            })
            is ScreenState.Error -> ErrorStage(s, onReset = { vm.reset() })
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Dewarp",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Raddrizza scansioni di libri con piega e angolazioni miste, mantenendo struttura, immagini e ordine.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IdleStage(
    pickedUri: Uri?,
    params: it.dewarp.engine.DewarpParams,
    splitTwoUp: Boolean,
    previewEnabled: Boolean,
    onPick: () -> Unit,
    onFigureAtt: (Float) -> Unit,
    onMaxDisp: (Float) -> Unit,
    onSmoothPx: (Float) -> Unit,
    onPolishDegree: (Int) -> Unit,
    onSplitToggle: (Boolean) -> Unit,
    onAutoRotateToggle: (Boolean) -> Unit,
    onPreviewToggle: (Boolean) -> Unit,
    onGpuToggle: (Boolean) -> Unit,
    onEngineChange: (String) -> Unit,
    onStart: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DropZone(
            uri = pickedUri,
            onClick = onPick,
        )

        OptionsCard(
            params = params,
            splitTwoUp = splitTwoUp,
            previewEnabled = previewEnabled,
            onFigureAtt = onFigureAtt,
            onMaxDisp = onMaxDisp,
            onSmoothPx = onSmoothPx,
            onPolishDegree = onPolishDegree,
            onSplitToggle = onSplitToggle,
            onAutoRotateToggle = onAutoRotateToggle,
            onPreviewToggle = onPreviewToggle,
            onGpuToggle = onGpuToggle,
            onEngineChange = onEngineChange,
        )

        Button(
            onClick = onStart,
            enabled = pickedUri != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("Raddrizza il PDF", fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DropZone(uri: Uri?, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .border(BorderStroke(1.5.dp, outline), RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = uri?.lastPathSegment ?: "Apri un PDF",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (uri == null) "Tocca per scegliere una scansione" else "Tocca per cambiare",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OptionsCard(
    params: it.dewarp.engine.DewarpParams,
    splitTwoUp: Boolean,
    previewEnabled: Boolean,
    onFigureAtt: (Float) -> Unit,
    onMaxDisp: (Float) -> Unit,
    onSmoothPx: (Float) -> Unit,
    onPolishDegree: (Int) -> Unit,
    onSplitToggle: (Boolean) -> Unit,
    onAutoRotateToggle: (Boolean) -> Unit,
    onPreviewToggle: (Boolean) -> Unit,
    onGpuToggle: (Boolean) -> Unit,
    onEngineChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Opzioni", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        EngineSelector(current = params.engine, onChange = onEngineChange)

        SliderRow(
            label = "Warp sulle figure",
            value = params.figureAttenuation.toFloat(),
            valueRange = 0f..1f,
            valueLabel = "${(params.figureAttenuation * 100).toInt()}%",
            onChange = onFigureAtt,
        )
        SliderRow(
            label = "Limite spostamento",
            value = params.maxDisplacementFrac.toFloat(),
            valueRange = 0.01f..0.10f,
            valueLabel = "${(params.maxDisplacementFrac * 100).toInt()}%",
            onChange = onMaxDisp,
        )
        if (params.engine == "polyline") {
            SliderRow(
                label = "Smoothing baseline",
                value = params.polylineSmoothPx.toFloat(),
                valueRange = 11f..251f,
                valueLabel = "${params.polylineSmoothPx} px",
                onChange = onSmoothPx,
            )
            PolishDegreeRow(
                current = params.polylinePolishDegree,
                onChange = onPolishDegree,
            )
        }
        SwitchRow(
            label = "Anteprima per pagina (regola crop manualmente)",
            checked = previewEnabled,
            onChange = onPreviewToggle,
        )
        SwitchRow(
            label = "Rotazione automatica 90°",
            checked = params.autoRotate,
            onChange = onAutoRotateToggle,
        )
        SwitchRow(
            label = "Spezza pagine doppie sul gutter",
            checked = splitTwoUp,
            onChange = onSplitToggle,
        )
        SwitchRow(
            label = "Usa GPU (sperimentale)",
            checked = params.useGpu,
            onChange = onGpuToggle,
        )
    }
}

@Composable
private fun PolishDegreeRow(current: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Polish baseline", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = current == 0, onClick = { onChange(0) }, label = { Text("Off") })
            FilterChip(selected = current == 1, onClick = { onChange(1) }, label = { Text("Lineare") })
            FilterChip(selected = current == 2, onClick = { onChange(2) }, label = { Text("Curvo") })
        }
        Text(
            text = when (current) {
                0 -> "Polyline pura. Segue il dato letteralmente."
                1 -> "Linea retta locale sulla baseline lisciata. Sweet spot: elimina wobble residuo."
                else -> "Parabola locale. Tendenzialmente overfitta i residui."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SliderRow(label: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, valueLabel: String, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = valueRange)
    }
}

@Composable
private fun EngineSelector(current: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Motore di dewarp",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = current == "polynomial",
                onClick = { onChange("polynomial") },
                label = { Text("Polinomiale") },
            )
            FilterChip(
                selected = current == "polyline",
                onClick = { onChange("polyline") },
                label = { Text("Polyline") },
            )
        }
        Text(
            text = if (current == "polynomial")
                "Fit di grado 2 sulla midline. Ottimo su pagine con tante righe regolari."
            else
                "Polyline samplate sulla baseline. Robusto su pagine con figure grandi o poche righe.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PreviewBuildingStage(s: ScreenState.PreviewBuilding) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Costruzione anteprima", style = MaterialTheme.typography.titleLarge)
        Text(
            "Pagina ${s.current + 1} di ${s.total.coerceAtLeast(1)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val progress = if (s.total > 0) s.current.toFloat() / s.total else 0f
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun PreviewStage(
    state: ScreenState.Preview,
    params: it.dewarp.engine.DewarpParams,
    onIndexChange: (Int) -> Unit,
    onCornerDrag: (cornerIdx: Int, x: Float, y: Float) -> Unit,
    onResetCorners: () -> Unit,
    onSmoothPx: (Float) -> Unit,
    onPolishDegree: (Int) -> Unit,
    onEngineChange: (String) -> Unit,
    onParamsChanged: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (state.pages.isEmpty()) return
    val page = state.pages[state.currentIndex]

    // Debounce: dopo che gli slider hanno smesso di muoversi per 300ms,
    // ricalcoliamo le polyline della pagina corrente con i nuovi parametri.
    val firstRun = remember { mutableStateOf(true) }
    LaunchedEffect(params.engine, params.polylineSmoothPx, params.polylinePolishDegree, state.currentIndex) {
        if (firstRun.value) { firstRun.value = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        onParamsChanged()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Pagina ${state.currentIndex + 1} / ${state.pages.size}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${page.baselines.size} righe rilevate",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(page.bitmap.width.toFloat() / page.bitmap.height.toFloat())
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp)),
        ) {
            Image(
                bitmap = page.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
            )
            CropAndBaselineOverlay(
                page = page,
                onCornerDrag = onCornerDrag,
                modifier = Modifier.matchParentSize(),
            )
        }
        // Navigazione + reset
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { onIndexChange((state.currentIndex - 1).coerceAtLeast(0)) },
                enabled = state.currentIndex > 0,
                modifier = Modifier.weight(1f),
            ) { Text("◀ Prec.") }
            FilledTonalButton(
                onClick = onResetCorners,
                modifier = Modifier.weight(1f),
            ) { Text("Reset crop") }
            FilledTonalButton(
                onClick = { onIndexChange((state.currentIndex + 1).coerceAtMost(state.pages.size - 1)) },
                enabled = state.currentIndex < state.pages.size - 1,
                modifier = Modifier.weight(1f),
            ) { Text("Succ. ▶") }
        }

        // Card parametri live (polyline si aggiornano al volo)
        PreviewParamsCard(
            params = params,
            onSmoothPx = onSmoothPx,
            onPolishDegree = onPolishDegree,
            onEngineChange = onEngineChange,
        )

        // Conferma / annulla
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("Annulla")
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .weight(2f)
                    .height(48.dp),
            ) { Text("Processa tutto") }
        }
        Text(
            "Trascina i 4 cerchi rossi per il crop. Le polyline azzurre mostrano dove l'engine rilevera' le righe; muovi gli slider e guarda come cambiano in tempo reale.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewParamsCard(
    params: it.dewarp.engine.DewarpParams,
    onSmoothPx: (Float) -> Unit,
    onPolishDegree: (Int) -> Unit,
    onEngineChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Parametri (live)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = params.engine == "polynomial",
                onClick = { onEngineChange("polynomial") },
                label = { Text("Polinomiale") },
            )
            FilterChip(
                selected = params.engine == "polyline",
                onClick = { onEngineChange("polyline") },
                label = { Text("Polyline") },
            )
        }
        if (params.engine == "polyline") {
            SliderRow(
                label = "Smoothing baseline",
                value = params.polylineSmoothPx.toFloat(),
                valueRange = 11f..251f,
                valueLabel = "${params.polylineSmoothPx} px",
                onChange = onSmoothPx,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Polish:", style = MaterialTheme.typography.bodyMedium)
                FilterChip(selected = params.polylinePolishDegree == 0,
                    onClick = { onPolishDegree(0) }, label = { Text("Off") })
                FilterChip(selected = params.polylinePolishDegree == 1,
                    onClick = { onPolishDegree(1) }, label = { Text("Lineare") })
                FilterChip(selected = params.polylinePolishDegree == 2,
                    onClick = { onPolishDegree(2) }, label = { Text("Curvo") })
            }
        }
    }
}

@Composable
private fun CropAndBaselineOverlay(
    page: PreviewPage,
    onCornerDrag: (cornerIdx: Int, x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragIdx by remember(page) { mutableIntStateOf(-1) }
    val cornerColor = Color(0xFFB04632)
    val baselineColor = Color(0xFF3060FF)
    val cornerInner = Color(0xFFF7F4EE)
    Canvas(
        modifier = modifier.pointerInput(page) {
            detectDragGestures(
                onDragStart = { offset ->
                    val touchPx = 56f
                    var best = -1; var bestDist = Float.MAX_VALUE
                    for ((i, c) in page.cropCorners.withIndex()) {
                        val cx = c.first * size.width; val cy = c.second * size.height
                        val d = kotlin.math.hypot(offset.x - cx, offset.y - cy)
                        if (d < touchPx && d < bestDist) { best = i; bestDist = d }
                    }
                    dragIdx = best
                },
                onDrag = { change, _ ->
                    if (dragIdx >= 0) {
                        val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                        val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                        onCornerDrag(dragIdx, nx, ny)
                        change.consume()
                    }
                },
                onDragEnd = { dragIdx = -1 },
                onDragCancel = { dragIdx = -1 },
            )
        },
    ) {
        // Polyline (sotto, cosi' i corner restano sopra)
        for (bl in page.baselines) {
            if (bl.size < 2) continue
            val path = Path()
            path.moveTo(bl[0].first * size.width, bl[0].second * size.height)
            for (j in 1 until bl.size) {
                path.lineTo(bl[j].first * size.width, bl[j].second * size.height)
            }
            drawPath(path, baselineColor, style = Stroke(width = 2.5f))
        }
        // Quadrilatero crop
        val cs = page.cropCorners.map { Offset(it.first * size.width, it.second * size.height) }
        val cropPath = Path()
        cropPath.moveTo(cs[0].x, cs[0].y)
        for (i in 1 until cs.size) cropPath.lineTo(cs[i].x, cs[i].y)
        cropPath.close()
        drawPath(cropPath, cornerColor, style = Stroke(width = 3f))
        // Maniglie agli angoli
        for (c in cs) {
            drawCircle(cornerColor, radius = 26f, center = c)
            drawCircle(cornerInner, radius = 12f, center = c)
        }
    }
}

@Composable
private fun ProcessingStage(s: ScreenState.Processing) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Elaborazione", style = MaterialTheme.typography.titleLarge)
        Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val progress = if (s.total > 0) s.current.toFloat() / s.total else 0f
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun DoneStage(s: ScreenState.Done, onReset: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pronto", style = MaterialTheme.typography.headlineMedium)
        Text(
            "${s.pagesIn} pagine sorgente, ${s.pagesOut} pagine raddrizzate.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(s.outputUri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = Intent.createChooser(view, "Apri PDF con")
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(chooser)
                    } catch (_: android.content.ActivityNotFoundException) { }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Apri PDF")
            }
            FilledTonalButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Nuovo")
            }
        }
    }
}

@Composable
private fun ErrorStage(s: ScreenState.Error, onReset: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Errore", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(s.message, color = MaterialTheme.colorScheme.onErrorContainer)
        Button(onClick = onReset) { Text("Riprova") }
    }
}
