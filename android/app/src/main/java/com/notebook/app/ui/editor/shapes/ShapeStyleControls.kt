package com.notebook.app.ui.editor.shapes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Instant, guarded property edits. Original opacity, arrowhead, transform and identity are retained. */
@Composable
internal fun ShapeStyleControls(
    selection: EditorShapeSelection,
    enabled: Boolean,
    onEdit: (CompletedShapeEdit) -> Unit,
) {
    val shape = selection.shape
    fun apply(edit: CompletedShapeEdit) { if (!edit.candidate.unchanged) onEdit(edit) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Outline color")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("blue" to "#2255AA", "red" to "#CC3333", "black" to "#222222").forEach { (name, color) ->
                FilterChip(
                    selected = shape.stroke.colorHex.equals(color, ignoreCase = true),
                    enabled = enabled,
                    onClick = { apply(selection.style(outlineColor = color)) },
                    label = { Text("Outline $name") },
                )
            }
        }
        Text("Outline width")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Thin" to 1.0, "Medium" to 3.0, "Thick" to 6.0).forEach { (name, width) ->
                FilterChip(
                    selected = shape.stroke.widthDu == width,
                    enabled = enabled,
                    onClick = { apply(selection.style(outlineWidth = width)) },
                    label = { Text(name) },
                )
            }
        }
        if (shape.kind == CanonicalShapeKind.RECTANGLE || shape.kind == CanonicalShapeKind.ELLIPSE) {
            Text("Fill")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = shape.fill == null, enabled = enabled,
                    onClick = { apply(selection.style(fill = null)) }, label = { Text("No fill") })
                listOf("blue" to "#2255AA", "red" to "#CC3333", "yellow" to "#FFE45C").forEach { (name, color) ->
                    FilterChip(
                        selected = shape.fill?.colorHex.equals(color, ignoreCase = true),
                        enabled = enabled,
                        onClick = { apply(selection.style(fill =
                            CanonicalShapeFill(color, shape.fill?.opacity ?: 1.0))) },
                        label = { Text("Fill $name") },
                    )
                }
            }
        }
    }
}
