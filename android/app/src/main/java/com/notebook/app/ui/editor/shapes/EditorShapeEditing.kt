package com.notebook.app.ui.editor.shapes

import com.notebook.app.ui.editor.ink.CanonicalElementAffine

/** Transient selection of one existing canonical row, never a second document model. */
class EditorShapeSelection internal constructor(
    internal val shape: StoredCanonicalShape,
    val expectedRevision: Long,
) {
    val pageId: String get() = shape.pageId
    val elementId: String get() = shape.source.id

    internal fun style(
        outlineColor: String = shape.stroke.colorHex,
        outlineWidth: Double = shape.stroke.widthDu,
        fill: CanonicalShapeFill? = shape.fill,
    ): CompletedShapeEdit {
        // Palette selections must preserve existing hex spelling when the painted color is equal.
        val color = if (outlineColor.equals(shape.stroke.colorHex, ignoreCase = true))
            shape.stroke.colorHex else outlineColor
        val proposedFill = if (fill != null && shape.fill != null &&
            fill.colorHex.equals(shape.fill.colorHex, ignoreCase = true) &&
            fill.opacity == shape.fill.opacity) shape.fill else fill
        return CompletedShapeEdit(ShapeEditCandidate(shape, stroke = shape.stroke.copy(
            colorHex = color, widthDu = outlineWidth,
        ), fill = proposedFill), expectedRevision)
    }
}

/** Immutable proposed values referencing the exact original envelope. No mutation or serialization. */
internal data class ShapeEditCandidate(
    val original: StoredCanonicalShape,
    val geometry: CanonicalShapeGeometry = original.geometry,
    val transform: CanonicalElementAffine = original.elementTransform,
    val stroke: CanonicalShapeStroke = original.stroke,
    val fill: CanonicalShapeFill? = original.fill,
) {
    val unchanged: Boolean get() = geometry == original.geometry &&
        transform.coefficients() == original.elementTransform.coefficients() &&
        stroke == original.stroke && fill == original.fill

    fun envelope(): String = CanonicalShapeAdapter.replace(
        original, geometry = geometry, stroke = stroke, fill = fill, elementTransform = transform,
    )
}

/** Completed replacement intent. Encoding runs off the input thread before C3 admission. */
class CompletedShapeEdit internal constructor(
    internal val candidate: ShapeEditCandidate,
    val expectedRevision: Long,
) {
    val pageId: String get() = candidate.original.pageId
    val elementId: String get() = candidate.original.source.id
    internal fun envelope(): String = candidate.envelope()
}
