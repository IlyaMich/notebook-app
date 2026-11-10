package com.notebook.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NoteEditorEraserModeTest {
    @Test fun partialIsDefaultAndFirstMode() {
        assertEquals(NoteEditorEraserMode.PARTIAL, NoteEditorEraserMode.entries.first())
    }

    @Test fun wholeStrokeModeIsDistinct() {
        assertNotEquals(NoteEditorEraserMode.PARTIAL, NoteEditorEraserMode.WHOLE_STROKE)
    }

    @Test fun modeLabelsAreUniqueAndNonempty() {
        val labels = NoteEditorEraserMode.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(true, labels.all { it.isNotBlank() })
    }
}
