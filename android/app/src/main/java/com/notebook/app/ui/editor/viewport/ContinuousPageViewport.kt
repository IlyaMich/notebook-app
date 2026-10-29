package com.notebook.app.ui.editor.viewport

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** Read-only snapshot for this checkpoint. Use page IDs/dimensions from DocumentRepository.load. */
data class PageViewportItem(
    val id: String,
    val widthDu: Double,
    val heightDu: Double,
    val elementCount: Int,
)

/**
 * EDT-03 checkpoint 3: a read-only Canvas of persisted, mixed-size pages.
 * Touch drag pans in either direction, and pinch zooms around the previous pointer centroid.
 * Geometry is transient: no document writes or canonical element changes occur.
 * Tool input arbitration, resize anchor restoration, and element rendering arrive later.
 */
@Composable
fun ContinuousPageViewport(
    pages: List<PageViewportItem>,
    modifier: Modifier = Modifier,
) {
    val layout = remember(pages) {
        ViewportGeometry.layoutOrNull(pages.map { PageSizeDu(it.id, it.widthDu, it.heightDu) })
    }
    val surround = MaterialTheme.colorScheme.surfaceVariant
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
    // Re-fit on layout/size changes. Scrolling is view-only state, never a persisted revision.
    val fitted = remember(layout, canvasSize, gutterPx) {
        ViewportGeometry.fitToWidthOrNull(layout, viewportPx, gutterPx)
    }
    var transform by remember(layout, canvasSize, gutterPx) {
        mutableStateOf(fitted)
    }
    val zoomPercent = transform?.let { active ->
        fitted?.let { initial ->
            ((active.scalePxPerDu / initial.scalePxPerDu) * 100.0).toInt()
        }
    } ?: 100

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(surround)
            .clipToBounds()
            .semantics {
                contentDescription = "Read-only note viewport with ${pages.size} pages"
                stateDescription = "Zoom $zoomPercent percent"
            }
            .onSizeChanged { canvasSize = it }
            .pointerInput(layout, canvasSize, gutterPx) {
                // Compose reports the previous-frame centroid and the current-frame pan delta.
                // Applying zoom at the former, then pan, keeps the focal document point under
                // the moving fingers until edge clamping becomes necessary.
                detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, _ ->
                    val previous = transform ?: return@detectTransformGestures
                    val fitScale = fitted?.scalePxPerDu ?: return@detectTransformGestures
                    val next = ViewportNavigation.updateOrNull(
                        layout = layout,
                        viewport = viewportPx,
                        gutterPx = gutterPx,
                        fitScalePxPerDu = fitScale,
                        current = previous,
                        centroidPreviousPx = PxPoint(centroid.x.toDouble(), centroid.y.toDouble()),
                        panPx = PxPoint(pan.x.toDouble(), pan.y.toDouble()),
                        zoomChange = zoom.toDouble(),
                    )
                    if (next != null) transform = next
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

            drawRect(Color.White, topLeft = Offset(left, top), size = Size(width, height))
            drawRect(pageOutline, topLeft = Offset(left, top), size = Size(width, height),
                style = Stroke(width = 1.dp.toPx()))
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
