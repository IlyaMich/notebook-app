
package com.notebook.app.ui.editor.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.viewport.PageSizeDu
import com.notebook.app.ui.editor.viewport.PxSize
import com.notebook.app.ui.editor.viewport.ViewportGeometry
import com.notebook.app.ui.editor.viewport.ViewportTransform
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorInkPreviewRenderingTest {

    private val pageId = "page-1"
    private val elementId =
        "11111111-2222-4333-8444-555555555555"

    private val layout = requireNotNull(
        ViewportGeometry.layoutOrNull(
            listOf(PageSizeDu(pageId, 120.0, 120.0))
        )
    )

    private fun capture(scale: Double): EditorPenPreview =
        EditorPenPreview(
            pointerId = 1L,
            pageId = pageId,
            startTransform = ViewportTransform(
                scalePxPerDu = scale,
                translationXPx = 0.0,
                translationYPx = 0.0,
            ),
            startViewport = PxSize(300.0, 300.0),
            samples = listOf(
                EditorPenPoint(
                    30.0 * scale,
                    50.0 * scale,
                    1000L,
                ),
                EditorPenPoint(
                    80.0 * scale,
                    50.0 * scale,
                    1020L,
                ),
                EditorPenPoint(
                    80.0 * scale,
                    90.0 * scale,
                    1040L,
                ),
            ),
        )

    private fun blankBitmap(): Bitmap =
        Bitmap.createBitmap(
            300,
            300,
            Bitmap.Config.ARGB_8888,
        ).apply {
            eraseColor(Color.WHITE)
        }

    private fun renderPreview(
        preview: EditorPenPreview,
        tool: EditorInkTool,
    ): Bitmap =
        blankBitmap().also { bitmap ->
            EditorInkPreviewCanvasRenderer.draw(
                canvas = Canvas(bitmap),
                preview = preview,
                brush = EditorInkBrushPresets.forTool(tool),
            )
        }

    private fun renderSaved(
        preview: EditorPenPreview,
        tool: EditorInkTool,
    ): Bitmap {
        val envelope = EditorPenDocumentCodec.encode(
            completed = preview,
            layout = layout,
            elementId = elementId,
            tool = tool,
        )

        val page = StoredPage(
            id = pageId,
            orderIndex = 0L,
            width = 120.0,
            height = 120.0,
            backgroundJson =
                """{"type":"blank","color":"#FFFFFF"}""",
            revision = 1L,
            elements = listOf(
                StoredElement(
                    id = elementId,
                    orderIndex = 0L,
                    kind = "stroke",
                    envelopeJson = envelope,
                    revision = 1L,
                )
            ),
        )

        val plan = CanonicalStrokeRenderPlan.build(
            page = page,
            positionedPage = layout.pages.single(),
            viewport = preview.startTransform,
        )

        return blankBitmap().also { bitmap ->
            CanonicalStrokeCanvasRenderer.draw(
                canvas = Canvas(bitmap),
                plan = plan,
            )
        }
    }

    private fun assertSimilarPixel(
        preview: Bitmap,
        saved: Bitmap,
        x: Int,
        y: Int,
        description: String,
    ) {
        val wet = preview.getPixel(x, y)
        val dry = saved.getPixel(x, y)

        assertTrue(
            "$description: preview should contain ink",
            wet != Color.WHITE,
        )
        assertTrue(
            "$description: saved rendering should contain ink",
            dry != Color.WHITE,
        )

        val differences = listOf(
            abs(Color.red(wet) - Color.red(dry)),
            abs(Color.green(wet) - Color.green(dry)),
            abs(Color.blue(wet) - Color.blue(dry)),
            abs(Color.alpha(wet) - Color.alpha(dry)),
        )

        assertTrue(
            "$description: wet=${Integer.toHexString(wet)}, " +
                "dry=${Integer.toHexString(dry)}",
            differences.all { it <= 12 },
        )
    }

    @Test
    fun highlighterDoesNotDarkenAtSegmentJoin() {
        val preview = renderPreview(
            capture(scale = 1.0),
            EditorInkTool.HIGHLIGHTER,
        )

        try {
            // Both positions are on the stroke centerline.
            // The corner must not receive an additional
            // translucent layer from another segment.
            val straight = preview.getPixel(55, 50)
            val corner = preview.getPixel(80, 50)

            assertTrue(
                Color.blue(straight) < 250
            )

            assertTrue(
                "Highlighter corner is darker than its segment",
                abs(
                    Color.blue(straight) -
                        Color.blue(corner)
                ) <= 6,
            )
        } finally {
            preview.recycle()
        }
    }

    @Test
    fun liveAndSavedBrushesMatchAtTwoZoomLevels() {
        EditorInkTool.entries.forEach { tool ->
            listOf(1.0, 2.0).forEach { scale ->
                val completed = capture(scale)
                val wet = renderPreview(completed, tool)
                val dry = renderSaved(completed, tool)

                try {
                    assertSimilarPixel(
                        wet,
                        dry,
                        (55 * scale).toInt(),
                        (50 * scale).toInt(),
                        "$tool at zoom $scale, horizontal",
                    )

                    assertSimilarPixel(
                        wet,
                        dry,
                        (80 * scale).toInt(),
                        (70 * scale).toInt(),
                        "$tool at zoom $scale, vertical",
                    )
                } finally {
                    wet.recycle()
                    dry.recycle()
                }
            }
        }
    }

    @Test
    fun singlePointPreviewMatchesSavedDot() {
        EditorInkTool.entries.forEach { tool ->
            val point = capture(scale = 2.0).copy(
                samples = listOf(
                    EditorPenPoint(
                        xPx = 100.0,
                        yPx = 120.0,
                        uptimeMs = 1000L,
                    )
                )
            )

            val wet = renderPreview(point, tool)
            val dry = renderSaved(point, tool)

            try {
                assertSimilarPixel(
                    wet,
                    dry,
                    100,
                    120,
                    "$tool single-point stroke",
                )
            } finally {
                wet.recycle()
                dry.recycle()
            }
        }
    }
}
