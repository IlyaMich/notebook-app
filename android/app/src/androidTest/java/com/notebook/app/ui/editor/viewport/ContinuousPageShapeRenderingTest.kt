package com.notebook.app.ui.editor.viewport

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.shapes.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ContinuousPageShapeRenderingTest {
    @get:Rule val rule = createComposeRule()
    private val pageId = "7d6acdd3-504f-4bb3-9c70-3ba7af56b4ba"
    private val viewport get() = rule.onNodeWithContentDescription("Read-only note viewport with 1 pages")
    private fun shape(kind: CanonicalShapeKind, index: Int, fill: String = "#00FF00"): StoredElement {
        val id = "10000000-0000-4000-8000-" + index.toString().padStart(12, '0')
        val geometry = when (kind) {
            CanonicalShapeKind.RECTANGLE -> CanonicalShapeGeometry.Frame(20.0, 40.0, 20.0, 20.0)
            CanonicalShapeKind.ELLIPSE -> CanonicalShapeGeometry.Frame(60.0, 40.0, 20.0, 20.0)
            CanonicalShapeKind.LINE -> CanonicalShapeGeometry.Endpoints(20.0, 80.0, 40.0, 80.0)
            CanonicalShapeKind.ARROW -> CanonicalShapeGeometry.Endpoints(60.0, 80.0, 80.0, 80.0)
        }
        return StoredElement(id, index.toLong(), "shape",
            CanonicalShapeAdapter.create(id, kind, geometry,
                CanonicalShapeStroke("#00FF00", 3.0, 1.0),
                CanonicalShapeFill(fill, 1.0),
                if (kind == CanonicalShapeKind.ARROW) CanonicalShapeArrowHead(12.0, 30.0) else null), 1)
    }
    private fun page(elements: List<StoredElement>) = StoredPage(pageId, 0, 100.0, 100.0,
        """{"type":"blank","color":"#FFFFFF"}""", 1, elements)
    private fun pixel(xDu: Double, yDu: Double): androidx.compose.ui.graphics.Color {
        val image = viewport.captureToImage()
        val gutter = image.width * 16.0 / 300.0
        val scale = (image.width - 2 * gutter) / 100.0
        return image.toPixelMap()[(gutter + xDu * scale).toInt(), (gutter + yDu * scale).toInt()]
    }

    @Test fun allFourStoredShapesAppearInProductionViewportWithoutChangingSource() {
        val source = page(CanonicalShapeKind.entries.mapIndexed { index, kind -> shape(kind, index) })
        val before = source.copy(elements = source.elements.toList())
        rule.setContent {
            Box(Modifier.size(300.dp)) {
                ContinuousPageViewport(listOf(PageViewportItem(pageId, 100.0, 100.0, 4)),
                    storedPages = mapOf(pageId to source), documentKey = "shapes-retained")
            }
        }
        rule.waitForIdle()
        for ((x, y) in listOf(30.0 to 50.0, 70.0 to 50.0, 30.0 to 80.0, 70.0 to 80.0)) {
            val color = pixel(x, y)
            assertTrue("Missing shape at ($x,$y)", color.green > 0.8f && color.red < 0.3f && color.blue < 0.3f)
        }
        assertEquals(before, source)
    }

    @Test fun confirmedSnapshotRefreshInvalidatesShapeCacheAndNavigationEmitsNoEdit() {
        val initial = page(listOf(shape(CanonicalShapeKind.RECTANGLE, 0, "#0000FF")))
        val current = mutableStateOf(initial)
        var edits = 0
        rule.setContent {
            Box(Modifier.size(300.dp)) {
                ContinuousPageViewport(listOf(PageViewportItem(pageId, 100.0, 100.0, 1)),
                    storedPages = mapOf(pageId to current.value), documentKey = "shape-refresh",
                    onPenStrokeCompleted = { _, _ -> edits++ },
                    onEraserSweepCaptured = { _, _, _ -> edits++ })
            }
        }
        rule.waitForIdle()
        assertTrue(pixel(30.0, 50.0).blue > 0.8f)
        rule.runOnIdle {
            current.value = initial.copy(revision = 2,
                elements = listOf(shape(CanonicalShapeKind.RECTANGLE, 0).copy(revision = 2)))
        }
        rule.waitForIdle()
        val refreshed = pixel(30.0, 50.0)
        assertTrue(refreshed.green > 0.8f && refreshed.blue < 0.3f)
        viewport.performTouchInput { swipe(Offset(140f, 140f), Offset(140f, 100f)) }
        rule.waitForIdle()
        assertEquals(0, edits)
        assertEquals(initial.elements.single().envelopeJson,
            shape(CanonicalShapeKind.RECTANGLE, 0, "#0000FF").envelopeJson)
        assertEquals(2L, current.value.revision)
    }
}
