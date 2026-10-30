package com.notebook.app.ui.editor.ink

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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.graphics.Matrix
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withSave
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.compose.createWithComposeColor
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

/** EDT-05 CP1A: debug-only Ink API viability probe, no document/repository calls. */
class InkProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { InkProbeScreen() }
        }
    }
}

@Composable
private fun InkProbeScreen() {
    val dryStrokes = remember { mutableStateListOf<Stroke>() }
    val brush = remember {
        Brush.createWithComposeColor(
            family = StockBrushes.pressurePen(),
            color = Color(0xFF2255AA),
            size = 6f,
            epsilon = 0.1f,
        )
    }
    val renderer = remember { CanvasStrokeRenderer.create() }
    val identityTransform = remember { Matrix() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Ink probe (debug only)", style = MaterialTheme.typography.titleMedium)
                Text("${dryStrokes.size} finished strokes")
                Text("In memory only - nothing is saved")
            }
            Button(onClick = { dryStrokes.clear() }) { Text("Clear") }
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color.White),
        ) {
            // Dry strokes are added synchronously in onStrokesFinished, avoiding wet/dry flicker.
            Canvas(modifier = Modifier.fillMaxSize()) {
                dryStrokes.forEach { stroke ->
                    // The renderer can mutate the underlying Android Canvas transform.
                    drawContext.canvas.nativeCanvas.withSave {
                        renderer.draw(
                            stroke = stroke,
                            canvas = this,
                            strokeToScreenTransform = identityTransform,
                        )
                    }
                }
            }
            InProgressStrokes(
                defaultBrush = brush,
                nextBrush = { brush },
                onStrokesFinished = { strokes -> dryStrokes.addAll(strokes) },
            )
        }
    }
}
