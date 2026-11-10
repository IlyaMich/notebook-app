package com.notebook.app.data.content

import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Decorators and fakes must never silently implement check-then-write semantics. */
class DocumentRepositoryConditionalCommitTest {
    @Test fun unimplementedConditionalCommitFailsClosedAndDoesNotCallLegacyCommit() = runBlocking {
        var calls = 0
        val fake = object : DocumentRepository {
            override suspend fun load(noteId: String) = StoredNoteContent(noteId, 0L, emptyList())
            override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit {
                calls++
                return DocumentCommit(noteId, 1L, 0L)
            }
        }
        try {
            fake.commitIfRevision("note", 0L,
                DocumentEdit.RemoveElements("page", listOf("element")))
            fail("Conditional commit must not fall back to unguarded write")
        } catch (_: UnsupportedOperationException) {
            // Deliberately fail closed.
        }
        assertEquals(0, calls)
    }
}
