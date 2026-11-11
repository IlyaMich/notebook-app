package com.notebook.app.ui.editor.shapes

import java.util.UUID

/** Creation choices are separate from canonical ink brushes. */
enum class EditorShapeTool(val label: String) {
    LINE("Line"), RECTANGLE("Rectangle"), ELLIPSE("Ellipse"), ARROW("Arrow");

    internal val kind: CanonicalShapeKind get() = CanonicalShapeKind.valueOf(name)
}

internal object ShapeCreationDefaults {
    val stroke = CanonicalShapeStroke("#2255AA", 3.0, 1.0)
    val arrowHead = CanonicalShapeArrowHead(12.0, 30.0)
}

/** A completed page-local command intent, not another document or persistence model. */
class CompletedShapeCreation internal constructor(
    val pageId: String,
    val expectedRevision: Long,
    internal val tool: EditorShapeTool,
    internal val geometry: CanonicalShapeGeometry,
) {
    /** Encode once off the input thread, before admission to the existing command queue. */
    internal fun envelope(): String = CanonicalShapeAdapter.create(
        elementId = UUID.randomUUID().toString(),
        kind = tool.kind,
        geometry = geometry,
        stroke = ShapeCreationDefaults.stroke,
        arrowHead = ShapeCreationDefaults.arrowHead.takeIf { tool == EditorShapeTool.ARROW },
    )
}
