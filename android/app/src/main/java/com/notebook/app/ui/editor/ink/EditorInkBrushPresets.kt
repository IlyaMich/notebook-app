
package com.notebook.app.ui.editor.ink

/**
 * Canonical brush properties.
 *
 * Width is expressed in document units (DU), never pixels.
 * These values are independent of viewport zoom and pan.
 */
internal data class EditorInkBrushPreset(
    val kind: String,
    val widthDu: Double,
    val colorHex: String,
    val opacity: Double,
)

/**
 * Initial EDT-09 drawing-tool presets.
 *
 * Future tool customization can replace these defaults
 * without changing the portable document format.
 */
internal object EditorInkBrushPresets {

    const val PEN_WIDTH_DU = 3.0
    const val PEN_COLOR_HEX = "#2255AA"

    fun forTool(tool: EditorInkTool): EditorInkBrushPreset =
        when (tool) {
            EditorInkTool.PEN -> EditorInkBrushPreset(
                kind = "pen",
                widthDu = PEN_WIDTH_DU,
                colorHex = PEN_COLOR_HEX,
                opacity = 1.0,
            )

            EditorInkTool.PENCIL -> EditorInkBrushPreset(
                kind = "pencil",
                widthDu = 2.0,
                colorHex = "#444444",
                opacity = 0.85,
            )

            EditorInkTool.HIGHLIGHTER -> EditorInkBrushPreset(
                kind = "highlighter",
                widthDu = 14.0,
                colorHex = "#FFE45C",
                opacity = 0.35,
            )

            // Fail closed rather than accidentally encoding an eraser gesture as ink.
            EditorInkTool.ERASER -> throw IllegalArgumentException(
                "Eraser is not a canonical drawing brush"
            )
        }
}
