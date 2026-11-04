
package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.viewport.ContinuousPageLayout
import com.notebook.app.ui.editor.viewport.PxPoint
import com.notebook.app.ui.editor.viewport.ViewportInteraction
import java.util.UUID
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

/**
 * Converts a completed temporary Pen capture into the
 * existing FMT-04 sampledPointsV1 element envelope.
 *
 * No Room, Compose, Jetpack Ink, or repository dependency.
 */
internal object EditorPenDocumentCodec {

    const val PEN_WIDTH_DU =
        EditorInkBrushPresets.PEN_WIDTH_DU

    const val PEN_COLOR_HEX =
        EditorInkBrushPresets.PEN_COLOR_HEX

    private const val MAX_EXACT_TIME_MS =
        9_007_199_254_740_991L

    fun encode(
        completed: EditorPenPreview,
        layout: ContinuousPageLayout,
        elementId: String = UUID.randomUUID().toString(),
        tool: EditorInkTool = EditorInkTool.PEN,
    ): String {
        UUID.fromString(elementId)
        val brush = EditorInkBrushPresets.forTool(tool)

        require(completed.samples.isNotEmpty()) {
            "Cannot save an empty stroke"
        }

        require(layout.pages.any { it.id == completed.pageId }) {
            "Stroke's starting page no longer exists"
        }

        val startCamera = completed.startTransform
        require(
            startCamera.scalePxPerDu.isFinite() &&
                startCamera.scalePxPerDu > 0.0
        ) {
            "Invalid stroke-start scale"
        }

        val firstTime = completed.samples.first().uptimeMs
        require(firstTime >= 0L) {
            "Invalid first sample timestamp"
        }

        var previousTime = firstTime

        val points = buildJsonArray {
            completed.samples.forEach { sample ->
                require(
                    sample.uptimeMs >= previousTime
                ) {
                    "Stroke timestamps are not monotonic"
                }

                val relativeTime = sample.uptimeMs - firstTime
                require(relativeTime <= MAX_EXACT_TIME_MS) {
                    "Stroke timestamp exceeds canonical precision"
                }

                previousTime = sample.uptimeMs

                // Use the camera captured at pointer-down.
                // Never use the current viewport camera here.
                val hit = ViewportInteraction.hitTestVisiblePage(
                    pointPx = PxPoint(
                        sample.xPx,
                        sample.yPx,
                    ),
                    layout = layout,
                    transform = startCamera,
                    viewport = completed.startViewport,
                )

                require(hit?.pageId == completed.pageId) {
                    "Stroke crosses its starting page boundary"
                }

                add(
                    buildJsonObject {
                        put("x", hit.localPointDu.x)
                        put("y", hit.localPointDu.y)
                        put("tMs", relativeTime)
                    }
                )
            }
        }

        val envelope = buildJsonObject {
            put("id", elementId)
            put("type", "stroke")

            put(
                "transform",
                buildJsonArray {
                    add(1.0)
                    add(0.0)
                    add(0.0)
                    add(1.0)
                    add(0.0)
                    add(0.0)
                },
            )

            put("locked", false)

            put(
                "payload",
                buildJsonObject {
                    put("encoding", "sampledPointsV1")

                    put(
                        "brush",
                        buildJsonObject {
                            put("kind", brush.kind)
                            put("width", brush.widthDu)
                            put("color", brush.colorHex)
                            put("opacity", brush.opacity)
                        },
                    )

                    put("points", points)
                },
            )
        }

        return envelope.toString()
    }
}
