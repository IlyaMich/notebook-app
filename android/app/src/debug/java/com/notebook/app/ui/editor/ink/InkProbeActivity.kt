package com.notebook.app.ui.editor.ink

import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withSave
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.compose.createWithComposeColor
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer

/** CP1B remains debug-only and never accesses Room, repository, or note revisions. */
class InkProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Activity-scoped state survives configuration recreation but not an actual finish.
        val session = ViewModelProvider(this)[InkProbeSessionViewModel::class.java]
        setContent { MaterialTheme { InkProbeScreen(session) } }
    }
}

@Composable
private fun InkProbeScreen(session: InkProbeSessionViewModel) {
    // Canonical DU samples survive activity recreation in the retained ViewModel.
    // They are still in memory only: no Room, SavedState or on-disk representation.
    val canonicalStrokes = session.canonicalStrokes
    var canvasWidthPx by remember { mutableIntStateOf(0) }
    val liveBrush = remember {
        Brush.createWithComposeColor(
            family = StockBrushes.pressurePen(),
            color = Color(0xFF2255AA),
            size = 6f,
            epsilon = 0.1f,
        )
    }
    val renderer = remember { CanvasStrokeRenderer.create() }
    val identityTransform = remember { Matrix() }

    // Disposable native reconstruction cache. Rebuild from canonical whenever width changes.
    val rebuilt = remember(canonicalStrokes.size, canvasWidthPx) {
        if (canvasWidthPx > 0) {
            val scale = InkPixelScale(canvasWidthPx.toDouble() / 612.0)
            canonicalStrokes.map { JetpackInkProbeAdapter.rebuild(it, scale) }
        } else emptyList()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Ink probe (debug only)", style = MaterialTheme.typography.titleMedium)
                Text("${canonicalStrokes.size} finished strokes")
                Text("In memory only - nothing is saved")
                Text("Canonical DU -> rebuilt Jetpack Ink")
                session.captureError?.let { Text("Ink conversion error: $it") }
            }
            Button(onClick = {
                session.clear()
            }) { Text("Clear") }
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .background(Color.White)
                .onSizeChanged { canvasWidthPx = it.width },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                rebuilt.forEach { stroke ->
                    drawContext.canvas.nativeCanvas.withSave {
                        renderer.draw(
                            canvas = this,
                            stroke = stroke,
                            strokeToScreenTransform = identityTransform,
                        )
                    }
                }
            }
            InProgressStrokes(
                defaultBrush = liveBrush,
                nextBrush = { liveBrush },
                onStrokesFinished = { nativeStrokes ->
                    if (canvasWidthPx <= 0) {
                        session.captureError = "Canvas has no measured width"
                    } else {
                        val scale = InkPixelScale(canvasWidthPx.toDouble() / 612.0)
                        // The probe captures and rebuilds each completed native stroke.
                        // Failed conversions are explicit and never reported as finished.
                        nativeStrokes.forEach { nativeStroke ->
                            runCatching {
                                val canonical = JetpackInkProbeAdapter.capture(nativeStroke, scale)
                                JetpackInkProbeAdapter.rebuild(canonical, scale)
                                canonical
                            }.onSuccess {
                                canonicalStrokes.add(it)
                                session.captureError = null
                            }.onFailure { session.captureError = it.message ?: "Unknown failure" }
                        }
                    }
                },
            )
        }
    }
}
