package com.notebook.app.ui.editor.ink

import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.ViewModelProvider

/** Distinct debug launcher: CP1A/CP1B in-memory probe and production editor are untouched. */
class InkPersistenceDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = ViewModelProvider(this)[InkPersistenceDemoViewModel::class.java]
        session.start(applicationContext, intent.getStringExtra(InkPersistenceDemoViewModel.SESSION_EXTRA))
        setContent { MaterialTheme { InkPersistenceDemoScreen(session) } }
    }
}

@Composable
private fun InkPersistenceDemoScreen(session: InkPersistenceDemoViewModel) {
    var canvasWidthPx by remember { mutableIntStateOf(0) }
    val nativeBrush = remember {
        Brush.createWithComposeColor(
            family = StockBrushes.pressurePen(),
            color = Color(0xFF2255AA),
            size = 6f,
            epsilon = 0.1f,
        )
    }
    val renderer = remember { CanvasStrokeRenderer.create() }
    val identity = remember { Matrix() }
    val strokes = session.strokes.toList()
    val renderResult = remember(strokes, canvasWidthPx) {
        runCatching {
            if (canvasWidthPx > 0) {
                val scale = InkPixelScale(canvasWidthPx.toDouble() / InkPersistenceDemoViewModel.DOCUMENT_WIDTH_DU)
                strokes.map { JetpackInkProbeAdapter.rebuild(it.canonical, scale) }
            } else emptyList()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Room ink demo (debug only)", style = MaterialTheme.typography.titleMedium)
            Text("${session.savedCount} saved strokes; ${session.pendingCount} pending")
            Text(session.status)
            Text("Dedicated debug database; production library is untouched")
            Text("Draw above the upper half for rotation checks")
            renderResult.exceptionOrNull()?.let { error ->
                Text("Native render error: ${error.message ?: error.javaClass.simpleName}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = session.isReady && !session.isSaving && session.pendingCount == 0,
                    onClick = { session.reloadFromDisk() },
                ) { Text("Reload from disk") }
                if (session.pendingCount > 0 && !session.isSaving && session.saveError != null) {
                    Button(onClick = { session.retryPending() }) { Text("Retry save") }
                }
            }
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .background(Color.White)
                .onSizeChanged { canvasWidthPx = it.width },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                renderResult.getOrNull()?.forEach { stroke ->
                    drawContext.canvas.nativeCanvas.withSave {
                        renderer.draw(
                            canvas = this,
                            stroke = stroke,
                            strokeToScreenTransform = identity,
                        )
                    }
                }
            }
            // The overlay exists only when the note has loaded. Never accept input during a reload.
            if (session.isReady) {
                InProgressStrokes(
                    defaultBrush = nativeBrush,
                    nextBrush = { nativeBrush },
                    onStrokesFinished = { completed ->
                        completed.forEach { stroke ->
                            session.captureNative(stroke, canvasWidthPx)
                        }
                    },
                )
            }
        }
    }
}
