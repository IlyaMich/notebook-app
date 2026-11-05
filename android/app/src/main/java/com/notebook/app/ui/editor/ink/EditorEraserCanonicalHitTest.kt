
package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserTransformedGeometry.Affine
import com.notebook.app.ui.editor.ink.EditorEraserTransformedGeometry.Result
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Read-only hit-testing against existing canonical strokes.
 *
 * Uses the same decoded stroke representation consumed
 * by CanonicalStrokeRenderPlan.
 *
 * Results are geometric candidates, NOT authorization
 * to execute a destructive DocumentEdit.
 *
 * No Room writes, schema changes, or element mutations.
 */
internal object EditorEraserCanonicalHitTest {

    data class ScanResult(
        val hitElementIds: List<String>,
        val lockedElementIds: List<String>,
        val unsupportedTransformElementIds: List<String>,
    ) {
        val hasUnsupportedTransforms: Boolean
            get() = unsupportedTransformElementIds.isNotEmpty()
    }

    fun scan(
        page: StoredPage,
        eraserPointsPageDu: List<Point>,
        eraserRadiusPageDu: Double,
    ): ScanResult {
        require(
            page.width.isFinite() &&
                page.height.isFinite() &&
                page.width > 0.0 &&
                page.height > 0.0
        ) {
            "Invalid page dimensions"
        }

        require(
            eraserRadiusPageDu.isFinite() &&
                eraserRadiusPageDu >= 0.0
        ) {
            "Invalid eraser radius"
        }

        require(
            eraserPointsPageDu.all {
                it.x.isFinite() && it.y.isFinite()
            }
        ) {
            "Invalid eraser coordinates"
        }

        val empty = ScanResult(
            hitElementIds = emptyList(),
            lockedElementIds = emptyList(),
            unsupportedTransformElementIds = emptyList(),
        )

        if (eraserPointsPageDu.isEmpty()) {
            return empty
        }

        // Reuse the production canonical stroke decoder.
        // Do not introduce another sampledPointsV1 parser.
        val strokes =
            StoredCanonicalStrokeDecoder.fromPage(page)

        val elementsById = page.elements.associateBy { it.id }

        require(elementsById.size == page.elements.size) {
            "Duplicate stored element IDs"
        }

        val hits = mutableListOf<String>()
        val locked = mutableListOf<String>()
        val unsupported = mutableListOf<String>()

        for (stroke in strokes) {
            require(stroke.pageId == page.id) {
                "Stroke belongs to a different page"
            }

            val storedElement =
                elementsById[stroke.elementId]
                    ?: throw IllegalArgumentException(
                        "Decoded stroke has no stored element"
                    )

            // Locked is part of the canonical element
            // envelope, not the stroke brush geometry.
            val envelope = Json.parseToJsonElement(
                storedElement.envelopeJson
            ).jsonObject

            val isLocked = envelope["locked"]
                ?.jsonPrimitive
                ?.booleanOrNull
                ?: throw IllegalArgumentException(
                    "Missing or invalid locked property"
                )

            if (isLocked) {
                locked += stroke.elementId
                continue
            }

            // Use the exact canonical transform already
            // consumed by CanonicalStrokeRenderPlan.
            val t = stroke.elementTransform.coefficients()

            require(t.size == 6) {
                "Invalid canonical element transform"
            }

            val affine = Affine(
                a = t[0],
                b = t[1],
                c = t[2],
                d = t[3],
                tx = t[4],
                ty = t[5],
            )

            val points = stroke.samples.map { sample ->
                Point(
                    x = sample.xDu,
                    y = sample.yDu,
                )
            }

            when (
                EditorEraserTransformedGeometry.hitTest(
                    strokePointsLocalDu = points,
                    strokeWidthLocalDu =
                        stroke.brush.widthDu,
                    transform = affine,
                    eraserPointsPageDu =
                        eraserPointsPageDu,
                    eraserRadiusPageDu =
                        eraserRadiusPageDu,
                )
            ) {
                Result.HIT -> {
                    hits += stroke.elementId
                }

                Result.MISS -> Unit

                Result.UNSUPPORTED_TRANSFORM -> {
                    unsupported += stroke.elementId
                }
            }
        }

        return ScanResult(
            hitElementIds = hits,
            lockedElementIds = locked,
            unsupportedTransformElementIds = unsupported,
        )
    }
}
