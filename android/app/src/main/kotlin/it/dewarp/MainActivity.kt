package it.dewarp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
        if (uri != null && input != null) vm.process(input, uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                onPick = { openPdf.launch(arrayOf("application/pdf")) },
                onFigureAtt = { vm.setFigureAttenuation(it) },
                onMaxDisp = { vm.setMaxDisplacement(it) },
                onSplitToggle = { vm.setSplitTwoUp(it) },
                onGpuToggle = { vm.setUseGpu(it) },
                onStart = {
                    val name = "dewarped.pdf"
                    createPdf.launch(name)
                },
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
    onPick: () -> Unit,
    onFigureAtt: (Float) -> Unit,
    onMaxDisp: (Float) -> Unit,
    onSplitToggle: (Boolean) -> Unit,
    onGpuToggle: (Boolean) -> Unit,
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
            onFigureAtt = onFigureAtt,
            onMaxDisp = onMaxDisp,
            onSplitToggle = onSplitToggle,
            onGpuToggle = onGpuToggle,
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
    onFigureAtt: (Float) -> Unit,
    onMaxDisp: (Float) -> Unit,
    onSplitToggle: (Boolean) -> Unit,
    onGpuToggle: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Opzioni", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
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
            FilledTonalButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Nuovo PDF")
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
