package com.notebook.app.ui.editor.viewport

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.ink.CanonicalStrokeCanvasRenderer
import com.notebook.app.ui.editor.ink.CanonicalStrokeRenderPlan
import com.notebook.app.ui.editor.ink.StoredCanonicalStroke
import com.notebook.app.ui.editor.ink.StoredCanonicalStrokeDecoder
import java.util.LinkedHashMap
import org.json.JSONObject
import android.util.Log
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import com.notebook.app.ui.editor.ink.EditorContact
import com.notebook.app.ui.editor.ink.EditorFingerMode
import com.notebook.app.ui.editor.ink.EditorGestureOwnership
import com.notebook.app.ui.editor.ink.EditorInkTool
import kotlin.math.abs
import kotlin.math.hypot
import androidx.compose.ui.input.pointer.PointerInputChange
import com.notebook.app.ui.editor.ink.EditorPenCapture
import com.notebook.app.ui.editor.ink.EditorPenPoint
import com.notebook.app.ui.editor.ink.EditorPenPreview
import androidx.compose.runtime.rememberUpdatedState
import com.notebook.app.ui.editor.ink.EditorPenDocumentCodec
import com.notebook.app.ui.editor.ink.EditorInkBrushPresets
import com.notebook.app.ui.editor.ink.EditorInkPreviewCanvasRenderer

/** Read-only snapshot for this checkpoint. Use page IDs/dimensions from DocumentRepository.load. */
data class PageViewportItem(
    val id: String,
    val widthDu: Double,
    val heightDu: Double,
    val elementCount: Int,
)

/**
 * Only blank-page colors are supported by this checkpoint.
 * Unsupported background rendering remains explicitly deferred.
 */
private fun blankPaperColorOrWhite(backgroundJson: String): Color =
    runCatching {
        val background = JSONObject(backgroundJson)

        if (background.optString("type") == "blank") {
            Color(
                android.graphics.Color.parseColor(
                    background.getString("color")
                )
            )
        } else {
            Color.White
        }
    }.getOrDefault(Color.White)

private fun PointerType.toEditorContact(): EditorContact =
    when (this) {
        PointerType.Stylus -> EditorContact.STYLUS
        PointerType.Touch -> EditorContact.FINGER
        PointerType.Eraser -> EditorContact.ERASER
        PointerType.Mouse -> EditorContact.MOUSE
        else -> EditorContact.UNKNOWN
    }

/**
 * EDT-03 checkpoint 4: a read-only Canvas of persisted, mixed-size pages.
 * Touch drag pans, pinch zooms, and taps resolve page-local hit targets for later tools.
 * On window/layout resize, an anchor and relative zoom are preserved where edge clamping allows.
 * Geometry is transient: no document writes or canonical element changes occur.
 * Tool input arbitration and element rendering arrive in later issues.
 */
@Composable
fun ContinuousPageViewport(
    pages: List<PageViewportItem>,
    modifier: Modifier = Modifier,
    documentKey: String = pages.firstOrNull()?.id.orEmpty(),
    storedPages: Map<String, StoredPage> = emptyMap(),
    fingerWritingPreview: Boolean = false,
    selectedTool: EditorInkTool = EditorInkTool.PEN,
    onPenStrokeCompleted: (String, String) -> Unit = { _, _ -> },
    onPenStrokeError: (String) -> Unit = {},
) {
    val latestOnPenStrokeCompleted by
    rememberUpdatedState(onPenStrokeCompleted)

    val latestOnPenStrokeError by
    rememberUpdatedState(onPenStrokeError)

    val layout = remember(pages) {
        ViewportGeometry.layoutOrNull(pages.map { PageSizeDu(it.id, it.widthDu, it.heightDu) })
    }
    val surround = MaterialTheme.colorScheme.surfaceVariant

    // EDT-07: Preserve the existing STO-06 blank-page
    // color behavior using the confirmed stored snapshot.
    // Patterned and PDF backgrounds require later renderers.
    val pageColors = remember(storedPages) {
        storedPages.mapValues { (_, page) ->
            blankPaperColorOrWhite(page.backgroundJson)
        }
    }
    if (layout == null || layout.pages.isEmpty()) {
        Box(modifier.fillMaxSize().background(surround), contentAlignment = Alignment.Center) {
            Text(if (layout == null) "Cannot display invalid page dimensions" else "This note has no pages")
        }
        return
    }

    // Pointer events and canvas dimensions are in physical pixels, not DP or document units.
    val density = LocalDensity.current
    val gutterPx = with(density) { 16.dp.toPx().toDouble() }
    val labelInsetPx = with(density) { 16.dp.toPx() }
    val labelBaselinePx = with(density) { 30.dp.toPx() }
    val detailBaselinePx = with(density) { 51.dp.toPx() }
    val pageOutline = MaterialTheme.colorScheme.outline
    val titlePaint = remember(density.density, density.fontScale) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.DKGRAY
            textSize = with(density) { 14.sp.toPx() }
        }
    }
    val detailPaint = remember(density.density, density.fontScale) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.GRAY
            textSize = with(density) { 11.sp.toPx() }
        }
    }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val viewportPx = PxSize(canvasSize.width.toDouble(), canvasSize.height.toDouble())
    val fitted = remember(layout, canvasSize, gutterPx) {
        ViewportGeometry.fitToWidthOrNull(layout, viewportPx, gutterPx)
    }
    // Keep the previous measured viewport with its transform. Simply keying remember() by
    // canvasSize would reset to the first page on every rotation or split-screen resize.
    var session by remember(documentKey) { mutableStateOf<ViewportSession?>(null) }
    var selectedPageId by remember(documentKey) { mutableStateOf<String?>(null) }
    var livePenPreview by remember(documentKey) {
        mutableStateOf<EditorPenPreview?>(null)
    }
    var livePreviewTool by remember(documentKey) {
        mutableStateOf<EditorInkTool?>(null)
    }
    // remember survives only recomposition, not a rotation that recreates the Activity.
    // Save a minimal, note-keyed page-local bookmark that can be rebuilt for any screen size.
    var savedPageId by rememberSaveable(documentKey) { mutableStateOf("") }
    var savedXDu by rememberSaveable(documentKey) { mutableStateOf(0.0) }
    var savedYDu by rememberSaveable(documentKey) { mutableStateOf(0.0) }
    var savedRelativeZoom by rememberSaveable(documentKey) { mutableStateOf(1.0) }

    fun saveViewportAnchor(transformToSave: ViewportTransform) {
        val anchor = ViewportInteraction.captureAnchorOrNull(
            layout, viewportPx, gutterPx, transformToSave,
        ) ?: return
        savedPageId = anchor.pageId
        savedXDu = anchor.localXDu
        savedYDu = anchor.localYDu
        savedRelativeZoom = anchor.zoomRelativeToFit
    }

    LaunchedEffect(layout, canvasSize, gutterPx) {
        if (fitted != null) {
            val previous = session
            val next = when {
                previous != null -> ViewportInteraction.restoreAfterChangeOrNull(
                    previous.layout, previous.viewport, previous.gutterPx, previous.transform,
                    layout, viewportPx, gutterPx,
                ) ?: fitted
                savedPageId.isNotEmpty() -> ViewportInteraction.restoreAnchorOrNull(
                    ViewportAnchor(savedPageId, savedXDu, savedYDu, savedRelativeZoom),
                    layout, viewportPx, gutterPx,
                ) ?: fitted
                else -> fitted
            }
            session = ViewportSession(layout, viewportPx, gutterPx, next)
            saveViewportAnchor(next)
        }
        // If size becomes zero during rotation, retain the prior bookmark.
    }
    // Don't display stale positions for one frame while the new viewport is measured.
    val activeSession = session?.takeIf {
        it.layout == layout && it.viewport == viewportPx && it.gutterPx == gutterPx
    }
    val transform = activeSession?.transform

    // Cache canonical decoding separately from zoom/pan.
    // Keep only recently visible pages in memory.
    val decodedCache = remember(documentKey, storedPages) {
        object : LinkedHashMap<String, List<StoredCanonicalStroke>>(
            8, 0.75f, true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<
                    String, List<StoredCanonicalStroke>
                    >?
            ): Boolean = size > 6
        }
    }

    // Render plans depend on viewport geometry, but their
    // canonical sources do not. Only plan visible pages.
    val strokePlans = remember(
        layout, transform, viewportPx, storedPages
    ) {
        if (transform == null) {
            emptyMap()
        } else {
            buildMap {
                layout.pages.forEach { positioned ->
                    val source = storedPages[positioned.id]
                        ?: return@forEach

                    val origin = ViewportGeometry.pageToViewport(
                        DuPoint(0.0, 0.0),
                        positioned,
                        transform,
                    )

                    val widthPx =
                        positioned.widthDu * transform.scalePxPerDu
                    val heightPx =
                        positioned.heightDu * transform.scalePxPerDu

                    val visible =
                        origin.x.isFinite() &&
                            origin.y.isFinite() &&
                            widthPx.isFinite() &&
                            heightPx.isFinite() &&
                            origin.x < viewportPx.width &&
                            origin.y < viewportPx.height &&
                            origin.x + widthPx > 0.0 &&
                            origin.y + heightPx > 0.0

                    if (!visible) return@forEach

                    val plan = try {
                        val decoded = decodedCache.getOrPut(
                            positioned.id
                        ) {
                            StoredCanonicalStrokeDecoder.fromPage(source)
                        }

                        CanonicalStrokeRenderPlan.build(
                            page = source,
                            positionedPage = positioned,
                            viewport = transform,
                            decodedStrokes = decoded,
                        )
                    } catch (exception: RuntimeException) {
                        Log.w(
                            "ContinuousPageViewport",
                            "Unable to prepare ink preview for page ${positioned.id}",
                            exception,
                        )
                        null
                    }

                    if (plan != null) {
                        put(positioned.id, plan)
                    }
                }
            }
        }
    }
    val zoomPercent = transform?.let { current ->
        fitted?.let { initial ->
            ((current.scalePxPerDu / initial.scalePxPerDu) * 100.0).toInt()
        }
    } ?: 100
    val selectedPageNumber = pages.indexOfFirst { it.id == selectedPageId }
    val selectionLabel = if (selectedPageNumber < 0) "no page selected"
        else "selected page ${selectedPageNumber + 1}"

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(surround)
            .clipToBounds()
            .semantics {
                contentDescription = "Read-only note viewport with ${pages.size} pages"
                stateDescription = "Zoom $zoomPercent percent; $selectionLabel"
            }
            .onSizeChanged { canvasSize = it }
            .pointerInput(
                documentKey,
                layout,
                canvasSize,
                gutterPx,
                fingerWritingPreview,
                selectedTool,
            ) {
                val owner = EditorGestureOwnership()
                val capture = EditorPenCapture()

                val fingerMode = if (fingerWritingPreview) {
                    EditorFingerMode.WRITE
                } else {
                    EditorFingerMode.NAVIGATE
                }

                var drawingPointerId: Long? = null


                fun processEffects(
                    effects: List<EditorGestureOwnership.Effect>,
                    initialPoint: EditorPenPoint? = null,
                    cameraAtStart: ViewportTransform? = null,
                ) {
                    effects.forEach { effect ->
                        when (effect) {
                            is EditorGestureOwnership.Effect.Started -> {
                                val started = if (
                                    initialPoint != null &&
                                    cameraAtStart != null
                                ) {
                                    capture.start(
                                        pointerId = effect.stroke.pointerId,
                                        pageId = effect.stroke.pageId,
                                        transform = cameraAtStart,
                                        viewport = viewportPx,
                                        point = initialPoint,
                                    )
                                } else {
                                    null
                                }

                                if (started != null) {
                                    drawingPointerId = started.pointerId
                                    livePenPreview = started
                                    livePreviewTool = selectedTool
                                } else {
                                    drawingPointerId = null
                                    livePenPreview = null
                                    livePreviewTool = null
                                }
                            }


                            is EditorGestureOwnership.Effect.Finished -> {
                                if (
                                    drawingPointerId ==
                                    effect.stroke.pointerId
                                ) {
                                    val completed = capture.finish(
                                        effect.stroke.pointerId
                                    )

                                    drawingPointerId = null
                                    livePenPreview = null
                                    livePreviewTool = null

                                    if (completed == null) {
                                        latestOnPenStrokeError(
                                            "Completed Pen stroke has no capture data"
                                        )
                                    } else {
                                        // Conversion can fail if the completed
                                        // samples violate canonical geometry.
                                        // Never enqueue a partially converted stroke.
                                        val encoded = runCatching {
                                            EditorPenDocumentCodec.encode(
                                                completed = completed,
                                                layout = layout,
                                                tool = selectedTool,
                                            )
                                        }

                                        encoded.fold(
                                            onSuccess = { envelopeJson ->
                                                latestOnPenStrokeCompleted(
                                                    completed.pageId,
                                                    envelopeJson,
                                                )
                                            },
                                            onFailure = { exception ->
                                                latestOnPenStrokeError(
                                                    "Pen stroke rejected: " +
                                                        (exception.message
                                                            ?: "Invalid stroke geometry")
                                                )
                                            },
                                        )
                                    }
                                }
                            }


                            is EditorGestureOwnership.Effect.Cancelled -> {
                                capture.cancel()
                                drawingPointerId = null
                                livePenPreview = null
                                livePreviewTool = null
                            }
                        }
                    }
                }

                fun appendFromPointer(change: PointerInputChange) {
                    if (drawingPointerId != change.id.value) return

                    val current = capture.preview ?: return

                    val point = EditorPenPoint(
                        xPx = change.position.x.toDouble(),
                        yPx = change.position.y.toDouble(),
                        uptimeMs = change.uptimeMillis,
                    )

                    // Validate against the camera at stroke start.
                    // The active stroke must not change page ownership.
                    val hit = ViewportInteraction.hitTestVisiblePage(
                        pointPx = PxPoint(point.xPx, point.yPx),
                        layout = layout,
                        transform = current.startTransform,
                        viewport = current.startViewport,
                    )

                    if (hit?.pageId != current.pageId) {
                        processEffects(owner.onConfigurationChanged())
                        return
                    }

                    val updated = capture.append(
                        pointerId = change.id.value,
                        point = point,
                    )

                    if (updated == null) {
                        // Invalid input, timestamp or sample limit.
                        processEffects(owner.onConfigurationChanged())
                    } else {
                        livePenPreview = updated
                    }
                }


                var tapPointerId: Long? = null
                var tapEligible = false
                var transformStarted = false
                var panMotion = 0.0
                var zoomMotion = 0.0

                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(
                                PointerEventPass.Main,
                            )

                            // A platform-cancelled gesture can arrive as a consumed
                            // pointer-up event. Never treat that as a completed stroke.
                            //
                            // Check consumption BEFORE our own dispatcher consumes
                            // any pointer changes.
                            val cancelledRelease = event.changes.any { change ->
                                change.changedToUpIgnoreConsumed() && change.isConsumed
                            }

                            if (cancelledRelease) {
                                processEffects(owner.cancelAll())

                                tapPointerId = null
                                tapEligible = false
                                transformStarted = false
                                panMotion = 0.0
                                zoomMotion = 0.0

                                continue
                            }
                            val fingerCount = event.changes.count {
                                it.pressed && it.type == PointerType.Touch
                            }

                            // Register new contacts before processing movement.
                            event.changes
                                .filter { it.changedToDownIgnoreConsumed() }
                                .forEach { change ->
                                    val current = session?.takeIf {
                                        it.layout == layout &&
                                            it.viewport == viewportPx &&
                                            it.gutterPx == gutterPx
                                    }

                                    val pageId = current?.let {
                                        ViewportInteraction.hitTestVisiblePage(
                                            PxPoint(
                                                change.position.x.toDouble(),
                                                change.position.y.toDouble(),
                                            ),
                                            layout,
                                            it.transform,
                                            viewportPx,
                                        )?.pageId
                                    }

                                    processEffects(
                                        effects = owner.onDown(
                                            pointerId = change.id.value,
                                            contact = change.type.toEditorContact(),
                                            selectedTool = selectedTool,
                                            fingerMode = fingerMode,
                                            fingerCount = fingerCount,
                                            pageId = pageId,
                                        ),
                                        initialPoint = EditorPenPoint(
                                            xPx = change.position.x.toDouble(),
                                            yPx = change.position.y.toDouble(),
                                            uptimeMs = change.uptimeMillis,
                                        ),
                                        cameraAtStart = current?.transform,
                                    )

                                    val onlyContact =
                                        event.changes.count { it.pressed } == 1

                                    if (
                                        change.type == PointerType.Touch &&
                                        onlyContact &&
                                        owner.canNavigate()
                                    ) {
                                        tapPointerId = change.id.value
                                        tapEligible = true
                                    } else {
                                        tapEligible = false
                                    }

                                    if (drawingPointerId == change.id.value) {
                                        change.consume()
                                    }
                                }

                            // Only pressed finger contacts participate in
                            // navigation. Stylus input never changes the camera.
                            val movingFingers = event.changes.filter {
                                it.type == PointerType.Touch &&
                                    it.pressed &&
                                    it.previousPressed
                            }

                            if (
                                owner.canNavigate() &&
                                movingFingers.isNotEmpty()
                            ) {
                                val previousX = movingFingers
                                    .map { it.previousPosition.x.toDouble() }
                                    .average()
                                val previousY = movingFingers
                                    .map { it.previousPosition.y.toDouble() }
                                    .average()

                                val currentX = movingFingers
                                    .map { it.position.x.toDouble() }
                                    .average()
                                val currentY = movingFingers
                                    .map { it.position.y.toDouble() }
                                    .average()

                                val panX = currentX - previousX
                                val panY = currentY - previousY

                                val zoom = if (movingFingers.size > 1) {
                                    val before = movingFingers.map {
                                        hypot(
                                            it.previousPosition.x - previousX,
                                            it.previousPosition.y - previousY,
                                        )
                                    }.average()

                                    val after = movingFingers.map {
                                        hypot(
                                            it.position.x - currentX,
                                            it.position.y - currentY,
                                        )
                                    }.average()

                                    if (before > 0.5) after / before else 1.0
                                } else {
                                    1.0
                                }

                                panMotion += hypot(panX, panY)
                                val previousRadius = movingFingers.map {
                                    hypot(
                                        it.previousPosition.x - previousX,
                                        it.previousPosition.y - previousY,
                                    )
                                }.average()

                                zoomMotion += abs(1.0 - zoom) * previousRadius

                                if (
                                    !transformStarted &&
                                    (
                                        panMotion > viewConfiguration.touchSlop ||
                                            zoomMotion > viewConfiguration.touchSlop
                                        )
                                ) {
                                    transformStarted = true
                                    tapEligible = false
                                }

                                if (transformStarted) {
                                    val previous = session?.takeIf {
                                        it.layout == layout &&
                                            it.viewport == viewportPx &&
                                            it.gutterPx == gutterPx
                                    }

                                    val fitScale = fitted?.scalePxPerDu

                                    if (previous != null && fitScale != null) {
                                        val next = ViewportNavigation.updateOrNull(
                                            layout = layout,
                                            viewport = viewportPx,
                                            gutterPx = gutterPx,
                                            fitScalePxPerDu = fitScale,
                                            current = previous.transform,
                                            centroidPreviousPx = PxPoint(
                                                previousX,
                                                previousY,
                                            ),
                                            panPx = PxPoint(panX, panY),
                                            zoomChange = zoom,
                                        )

                                        if (next != null) {
                                            session = previous.copy(transform = next)
                                            saveViewportAnchor(next)
                                        }
                                    }

                                    movingFingers.forEach { it.consume() }
                                }
                            }

                            // Capture movement and the final pointer-up position
                            // before processing the Finished effect.
                            drawingPointerId?.let { id ->
                                event.changes
                                    .firstOrNull { it.id.value == id }
                                    ?.let { change ->
                                        if (
                                            change.previousPressed &&
                                            change.position != change.previousPosition
                                        ) {
                                            appendFromPointer(change)
                                        }

                                        // Only the current drawing owner may
                                        // consume this pointer's changes.
                                        if (drawingPointerId == id) {
                                            change.consume()
                                        }
                                    }
                            }

                            event.changes
                                .filter { it.changedToUpIgnoreConsumed() }
                                .forEach { change ->
                                    if (
                                        change.id.value == tapPointerId &&
                                        tapEligible &&
                                        !transformStarted
                                    ) {
                                        val current = session?.takeIf {
                                            it.layout == layout &&
                                                it.viewport == viewportPx &&
                                                it.gutterPx == gutterPx
                                        }

                                        if (current != null) {
                                            selectedPageId =
                                                ViewportInteraction.hitTestVisiblePage(
                                                    PxPoint(
                                                        change.position.x.toDouble(),
                                                        change.position.y.toDouble(),
                                                    ),
                                                    layout,
                                                    current.transform,
                                                    viewportPx,
                                                )?.pageId
                                        }
                                    }

                                    processEffects(owner.onUp(change.id.value))

                                    if (change.id.value == tapPointerId) {
                                        tapPointerId = null
                                    }
                                }

                            if (
                                event.changes.none {
                                    it.pressed && it.type == PointerType.Touch
                                }
                            ) {
                                tapPointerId = null
                                tapEligible = false
                                transformStarted = false
                                panMotion = 0.0
                                zoomMotion = 0.0
                            }
                        }
                    }
                } finally {
                    processEffects(owner.cancelAll())
                    capture.cancel()
                    drawingPointerId = null
                    livePenPreview = null
                    livePreviewTool = null
                }
            },
    ) {
        val active = transform ?: return@Canvas
        layout.pages.forEachIndexed { index, page ->
            val origin = ViewportGeometry.pageToViewport(DuPoint(0.0, 0.0), page, active)
            val left = origin.x.toFloat()
            val top = origin.y.toFloat()
            val width = (page.widthDu * active.scalePxPerDu).toFloat()
            val height = (page.heightDu * active.scalePxPerDu).toFloat()
            if (!left.isFinite() || !top.isFinite() || !width.isFinite() || !height.isFinite() ||
                width <= 0f || height <= 0f || left >= size.width || top >= size.height ||
                left + width <= 0f || top + height <= 0f
            ) return@forEachIndexed

            drawRect(
                color = pageColors[page.id] ?: Color.White,
                topLeft = Offset(left, top),
                size = Size(width, height),
            )
            drawRect(pageOutline, topLeft = Offset(left, top), size = Size(width, height),
                style = Stroke(width = 1.dp.toPx()))

            // EDT-07: Draw canonical ink from the persisted
            // page snapshot, independently of capture state.
            strokePlans[page.id]?.let { plan ->
                drawIntoCanvas { canvas ->
                    try {
                        CanonicalStrokeCanvasRenderer.draw(
                            canvas.nativeCanvas,
                            plan,
                        )
                    } catch (exception: RuntimeException) {
                        Log.w(
                            "ContinuousPageViewport",
                            "Unable to draw ink preview for page ${page.id}",
                            exception,
                        )
                    }
                }
            }

            livePenPreview
                ?.takeIf { it.pageId == page.id }
                ?.let { preview ->
                    val toolAtStart = livePreviewTool
                        ?: return@let

                    val brush = EditorInkBrushPresets.forTool(
                        toolAtStart
                    )

                    clipRect(
                        left = left,
                        top = top,
                        right = left + width,
                        bottom = top + height,
                    ) {
                        drawIntoCanvas { canvas ->
                            EditorInkPreviewCanvasRenderer.draw(
                                canvas = canvas.nativeCanvas,
                                preview = preview,
                                brush = brush,
                            )
                        }
                    }
                }

            // The same page rectangle clips placeholder text now and will clip editable
            // elements and live stroke previews once those renderers exist.
            clipRect(left = left, top = top, right = left + width, bottom = top + height) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(
                        "Page ${index + 1}", left + labelInsetPx, top + labelBaselinePx, titlePaint,
                    )
                    canvas.nativeCanvas.drawText(
                        "${page.widthDu} × ${page.heightDu} DU · " +
                            "${pages[index].elementCount} stored element(s)",
                        left + labelInsetPx, top + detailBaselinePx, detailPaint,
                    )
                }
            }
        }
    }
}

/** The viewport dimensions and its transient transform must move together on remeasurement. */
private data class ViewportSession(
    val layout: ContinuousPageLayout,
    val viewport: PxSize,
    val gutterPx: Double,
    val transform: ViewportTransform,
)

@Preview(name = "EDT-03 mixed-size page stack", widthDp = 600, heightDp = 700)
@Composable
private fun MixedSizePageViewportPreview() {
    MaterialTheme {
        ContinuousPageViewport(
            pages = listOf(
                PageViewportItem("portrait", 612.0, 792.0, 0),
                PageViewportItem("landscape", 792.0, 612.0, 3),
                PageViewportItem("a4", 595.276, 841.890, 1),
            ),
        )
    }
}
