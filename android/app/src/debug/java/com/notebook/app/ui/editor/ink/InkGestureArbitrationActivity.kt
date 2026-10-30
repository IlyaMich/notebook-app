package com.notebook.app.ui.editor.ink

import android.graphics.Matrix as AndroidMatrix
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke as ComposeStroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withSave
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.compose.createWithComposeColor
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.mutableStateListOf
import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.viewport.PageSizeDu
import com.notebook.app.ui.editor.viewport.PxPoint
import com.notebook.app.ui.editor.viewport.PxSize
import com.notebook.app.ui.editor.viewport.ViewportGeometry
import com.notebook.app.ui.editor.viewport.ViewportNavigation
import com.notebook.app.ui.editor.viewport.ViewportInteraction
import com.notebook.app.ui.editor.viewport.ViewportTransform
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.max

/** Debug-only gesture probe. Never opens the production editor or Room. */
class InkGestureArbitrationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = ViewModelProvider(this)[InkGestureDemoSession::class.java]
        setContent { MaterialTheme { InkGestureScreen(session) } }
    }
}

internal class InkGestureDemoSession : ViewModel() {
    val strokes = mutableStateListOf<InkViewportPageAdapter.Captured>()
    var rejected by mutableIntStateOf(0)
    var navigationUpdates by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
        private set
    // Changing the key disposes the native wet-ink view, including unfinished /
    // canceled render state that cannot be cleared through the Compose API.
    var inkLayerGeneration by mutableIntStateOf(0)
        private set

    fun reject(reason: String) {
        rejected += 1
        error = reason
        inkLayerGeneration += 1
    }

    fun abandonNativeInk() {
        inkLayerGeneration += 1
    }

    fun accept(stroke: InkViewportPageAdapter.Captured) {
        strokes.add(stroke)
        error = null
    }

    fun clear() {
        strokes.clear()
        rejected = 0
        error = null
        inkLayerGeneration += 1
    }
}

@Composable
private fun InkGestureScreen(session: InkGestureDemoSession) {
    val layout = remember {
        checkNotNull(ViewportGeometry.layoutOrNull(listOf(
            PageSizeDu("a4-portrait", 595.276, 841.890),
            PageSizeDu("letter-landscape", 792.0, 612.0),
        )))
    }
    var fingerDrawing by rememberSaveable { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val viewport = PxSize(size.width.toDouble(), size.height.toDouble())
    val gutterPx = 28.0
    val fit = remember(size) { ViewportGeometry.fitToWidthOrNull(layout, viewport, gutterPx) }
    var camera by remember { mutableStateOf<ViewportTransform?>(null) }
    var strokeStartCamera by remember { mutableStateOf<ViewportTransform?>(null) }
    val latestCamera by rememberUpdatedState(camera)
    val latestViewport by rememberUpdatedState(viewport)
    // Refit on resize; canonical page-local ink remains in the retained ViewModel.
    LaunchedEffect(size) { camera = fit }

    val brush = remember {
        Brush.createWithComposeColor(
            family = StockBrushes.pressurePen(),
            color = Color(0xFF2255AA),
            size = 6f,
            epsilon = 0.1f,
        )
    }
    val renderer = remember { CanvasStrokeRenderer.create() }
    val identity = remember { AndroidMatrix() }
    // Ink's maskPath describes HIDDEN regions, not the allowed drawing area.
    // Even-odd fill: mask the viewport rectangle minus the visible page rects.
    // The parent clips the entire drawing surface below the header.
    val inkMask = remember(camera, size, layout) {
        Path().apply {
            fillType = PathFillType.EvenOdd
            if (size.width > 0 && size.height > 0) {
                addRect(Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()))
            }
            val view = camera
            if (view != null && size.width > 0 && size.height > 0) {
                layout.pages.forEach { page ->
                    val topLeft = ViewportGeometry.worldToViewport(
                        DuPoint(page.originXDu, page.originYDu), view,
                    )
                    val left = max(0f, topLeft.x.toFloat())
                    val top = max(0f, topLeft.y.toFloat())
                    val right = min(size.width.toFloat(),
                        (topLeft.x + page.widthDu * view.scalePxPerDu).toFloat())
                    val bottom = min(size.height.toFloat(),
                        (topLeft.y + page.heightDu * view.scalePxPerDu).toFloat())
                    if (right > left && bottom > top) {
                        addRect(Rect(left, top, right, bottom))
                    }
                }
            }
        }
    }
    val stored = session.strokes.toList()
    val dryInk = remember(stored, camera) {
        stored.mapNotNull { captured ->
            runCatching {
                val transform = camera ?: return@runCatching null
                val projection = InkViewportPageAdapter.projectOrNull(
                    captured.pageId, captured.canonical, layout, transform,
                ) ?: return@runCatching null
                // Native dry strokes are disposable screen-PX projections. The
                // original page-local DU samples never change when the camera moves.
                val display = CanonicalInkStroke(
                    brush = captured.canonical.brush.copy(widthDu = projection.brushWidthPx),
                    points = projection.samples.map {
                        CanonicalInkPoint(it.xPx, it.yPx, it.elapsedTimeMs,
                            it.pressure, it.tiltRad, it.orientationRad)
                    },
                )
                captured.pageId to JetpackInkProbeAdapter.rebuild(display, InkPixelScale(1.0))
            }.getOrNull()
        }
    }
    // pointerInput stays installed across camera changes. Its callback reads the
    // latest state, avoiding cancellation/restart while a gesture is in flight.
    val handleNavigation by rememberUpdatedState<(Double, Double, Double, Double, Double) -> Unit>(
        { prevX, prevY, panX, panY, zoom ->
            val current = camera
            if (current != null && fit != null) {
                val updated = ViewportNavigation.updateOrNull(
                    layout, viewport, gutterPx, fit.scalePxPerDu, current,
                    PxPoint(prevX, prevY), PxPoint(panX, panY), zoom,
                )
                if (updated != null && updated != current) {
                    camera = updated
                    session.navigationUpdates += 1
                }
            }
        },
    )

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(10.dp).testTag("inkGestureHeader")) {
            Text("Ink gesture arbitration (debug only)", style = MaterialTheme.typography.titleMedium)
            Text("Stored strokes: ${session.strokes.size}; Rejected: ${session.rejected}", maxLines = 1)
            Text("Navigation updates: ${session.navigationUpdates}", maxLines = 1)
            Text(if (fingerDrawing) "Mode: Finger draw" else "Mode: Finger navigate")
            // ALWAYS occupy one line. In CP3B, inserting/removing the error
            // text resized the canvas, triggering LaunchedEffect(size) -> refit.
            Text(
                text = "Ink conversion: ${session.error ?: "Ready"}",
                color = if (session.error == null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("inkGestureStatus"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    fingerDrawing = !fingerDrawing
                    strokeStartCamera = null
                    session.abandonNativeInk()
                }) {
                    Text(if (fingerDrawing) "Use finger navigation" else "Enable finger drawing")
                }
                Button(onClick = { session.clear(); strokeStartCamera = null }) { Text("Clear") }
            }
            Text("Touch: pan/pinch in Navigate mode. Stylus: ink. Mouse: ink.",
                style = MaterialTheme.typography.bodySmall)
            Text("Emulator: use Finger drawing mode to draw with simulated touch.",
                style = MaterialTheme.typography.bodySmall)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE2E3E5))
                .clipToBounds()
                .onSizeChanged { size = it }
                .testTag("inkGestureCanvas")
                .pointerInput(fingerDrawing) {
                    val owner = InkPointerOwnership()
                    val startGate = InkPointerStartGate()
                    val writingPointers = mutableSetOf<Long>()
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                // Parent Initial pass runs before the child Ink layer.
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val contacts = event.changes.map { change ->
                                    val kind = when (change.type) {
                                        PointerType.Touch -> InkPointerOwnership.Kind.TOUCH
                                        PointerType.Stylus -> InkPointerOwnership.Kind.STYLUS
                                        else -> InkPointerOwnership.Kind.OTHER
                                    }
                                    InkPointerOwnership.Contact(change.id.value, kind, change.pressed)
                                }
                                val decision = owner.route(contacts, fingerDrawing)
                                // A stroke that starts outside every page must not
                                // reach the native Ink view even if it later enters.
                                // Only routing/navigation touch events are exempt.
                                event.changes.forEach { change ->
                                    val isDrawingPointer = when (change.type) {
                                        PointerType.Touch -> fingerDrawing
                                        PointerType.Stylus, PointerType.Mouse -> true
                                        else -> false
                                    }
                                    val down = change.pressed && !change.previousPressed
                                    val position = change.position
                                    val isInsidePage = if (down && isDrawingPointer) {
                                        val view = latestCamera
                                        view != null && ViewportInteraction.hitTestVisiblePage(
                                            PxPoint(position.x.toDouble(), position.y.toDouble()),
                                            layout, view, latestViewport,
                                        ) != null
                                    } else false
                                    if (down && isDrawingPointer && isInsidePage) {
                                        if (writingPointers.isEmpty()) strokeStartCamera = latestCamera
                                        writingPointers.add(change.id.value)
                                    }
                                    if (isDrawingPointer && startGate.shouldConsume(
                                            id = change.id.value,
                                            down = down,
                                            pressed = change.pressed,
                                            validStart = isInsidePage,
                                        )) {
                                        change.consume()
                                    }
                                    if (!change.pressed) writingPointers.remove(change.id.value)
                                }
                                val touches = event.changes.filter { it.type == PointerType.Touch }
                                if (decision.navigateTouch) {
                                    val moving = touches.filter { it.pressed && it.previousPressed }
                                    if (moving.isNotEmpty()) {
                                        val oldX = moving.map { it.previousPosition.x.toDouble() }.average()
                                        val oldY = moving.map { it.previousPosition.y.toDouble() }.average()
                                        val nowX = moving.map { it.position.x.toDouble() }.average()
                                        val nowY = moving.map { it.position.y.toDouble() }.average()
                                        val zoom = if (moving.size > 1) {
                                            val before = moving.map {
                                                hypot(it.previousPosition.x.toDouble() - oldX,
                                                    it.previousPosition.y.toDouble() - oldY)
                                            }.average()
                                            val after = moving.map {
                                                hypot(it.position.x.toDouble() - nowX,
                                                    it.position.y.toDouble() - nowY)
                                            }.average()
                                            if (before > 0.5) after / before else 1.0
                                        } else 1.0
                                        if (oldX != nowX || oldY != nowY || zoom != 1.0) {
                                            handleNavigation(oldX, oldY, nowX - oldX, nowY - oldY, zoom)
                                        }
                                    }
                                }
                                // Touch must be consumed even when stylus freezes camera;
                                // otherwise InProgressStrokes would create stray finger ink.
                                if (decision.consumeTouch) touches.forEach { it.consume() }
                            }
                        }
                    } finally { owner.reset(); startGate.reset(); writingPointers.clear() }
                },
        ) {
            Canvas(Modifier.fillMaxSize().clipToBounds()) {
                val view = camera ?: return@Canvas
                for (page in layout.pages) {
                    val topLeft = ViewportGeometry.worldToViewport(
                        DuPoint(page.originXDu, page.originYDu), view,
                    )
                    val w = page.widthDu * view.scalePxPerDu
                    val h = page.heightDu * view.scalePxPerDu
                    drawRect(Color.White, Offset(topLeft.x.toFloat(), topLeft.y.toFloat()),
                        Size(w.toFloat(), h.toFloat()))
                    drawRect(Color.Gray, Offset(topLeft.x.toFloat(), topLeft.y.toFloat()),
                        Size(w.toFloat(), h.toFloat()), style = ComposeStroke(width = 2f))
                    drawContext.canvas.nativeCanvas.withSave {
                        clipRect(topLeft.x.toFloat(), topLeft.y.toFloat(),
                            (topLeft.x + w).toFloat(), (topLeft.y + h).toFloat())
                        dryInk.filter { it.first == page.id }.forEach { (_, native) ->
                            renderer.draw(canvas = this, stroke = native, strokeToScreenTransform = identity)
                        }
                    }
                }
            }
            // The 1.0.0 Compose Ink API has no explicit clear() method. Re-key
            // its native view when a stroke is rejected or the user presses Clear.
            // A generation check ignores any callback arriving from the old view.
            key(session.inkLayerGeneration) {
            val generation = session.inkLayerGeneration
            InProgressStrokes(
                defaultBrush = brush,
                maskPath = inkMask,
                nextBrush = { brush },
                onStrokesFinished = { nativeStrokes: List<Stroke> ->
                    if (generation != session.inkLayerGeneration) return@InProgressStrokes
                    // Ink delivers a completed stroke after pointer-up; finger pan may
                    // already have changed the camera. Capture against pointer-down camera.
                    val transform = strokeStartCamera ?: camera
                    if (transform == null || size.width <= 0 || size.height <= 0) {
                        nativeStrokes.forEach {
                            session.reject("No valid viewport at stroke completion")
                        }
                    } else {
                        nativeStrokes.forEach { native ->
                            runCatching {
                                val samples = (0 until native.inputs.size).map { index ->
                                    val input = native.inputs.get(index)
                                    InkPixelSample(
                                        input.x.toDouble(), input.y.toDouble(), input.elapsedTimeMillis,
                                        if (input.hasPressure) input.pressure.toDouble() else null,
                                        if (input.hasTilt) input.tiltRadians.toDouble() else null,
                                        if (input.hasOrientation) input.orientationRadians.toDouble() else null,
                                    )
                                }
                                InkViewportPageAdapter.captureOrNull(
                                    samples, layout, transform, viewport,
                                    brushKind = "pen", brushWidthPx = 6.0,
                                    brushColorHex = "#2255AA", brushOpacity = 1.0,
                                )
                            }.onSuccess { converted ->
                                if (converted == null) {
                                    session.reject("Outside page or crosses page boundary")
                                } else {
                                    session.accept(converted)
                                }
                            }.onFailure {
                                session.reject(it.message ?: "Native input could not be converted")
                            }
                        }
                    }
                    strokeStartCamera = null
                },
            )
            }
        }
    }
}
