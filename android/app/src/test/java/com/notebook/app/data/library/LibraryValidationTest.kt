package com.notebook.app.data.library

import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.library.model.Notebook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class LibraryValidationTest {
    @Test
    fun acceptsCanonicalUuidAndRejectsPlaceholderId() {
        val uuid = UUID.randomUUID().toString()
        assertEquals(uuid, LibraryValidation.canonicalId(uuid))
        assertInvalid { LibraryValidation.canonicalId("sample-note") }
        assertInvalid { LibraryValidation.canonicalId(uuid.uppercase()) }
    }

    @Test
    fun requiresNonBlankNamesButPreservesEmptyNoteTitle() {
        assertEquals("Mathematics", LibraryValidation.nonBlankName("  Mathematics  "))
        assertInvalid { LibraryValidation.nonBlankName("  \n\t  ") }
    }

    @Test
    fun newPageMustBePositiveFiniteAndHaveBackground() {
        val good = InitialPageSpec.a4PortraitBlank()
        assertTrue(LibraryValidation.initialPage(good).width > 0.0)
        assertInvalid { LibraryValidation.initialPage(good.copy(width = 0.0)) }
        assertInvalid { LibraryValidation.initialPage(good.copy(height = Double.NaN)) }
        assertInvalid { LibraryValidation.initialPage(good.copy(width = Double.POSITIVE_INFINITY)) }
        assertInvalid { LibraryValidation.initialPage(good.copy(backgroundJson = " ")) }
    }

    @Test
    fun quickNotesRoleIsIndependentOfDisplayedName() {
        val notebook = Notebook(
            id = UUID.randomUUID().toString(),
            parentFolderId = null,
            name = "My scratchpad",
            sortOrder = 0L,
            systemRole = Notebook.QUICK_NOTES_ROLE,
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
        )
        assertTrue(notebook.isQuickNotes)
    }

    private fun assertInvalid(action: () -> Unit) {
        try {
            action()
            fail("Expected invalid input to be rejected")
        } catch (expected: LibraryOperationException) {
            assertEquals(LibraryErrorCode.INVALID_INPUT, expected.code)
        }
    }
}
