package com.notebook.app.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Foundation-only DAO/SQLite tests; canonical editor validation comes in 3A. */
@RunWith(AndroidJUnit4::class)
class PageElementFoundationTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "sto02-foundation-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, name)
        db.openHelper.writableDatabase
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test fun orderedRowsSurviveDatabaseReopenAndSingleElementUpdate() = runBlocking {
        val note = createFixtureNote()
        val a = page(note.id, 0)
        val b = page(note.id, 1)
        db.pageDao().insert(a)
        db.pageDao().insert(b)
        val back = element(a.id, 0, "Back")
        val front = element(a.id, 1, "Front")
        db.elementDao().insert(front)
        db.elementDao().insert(back)
        assertEquals(listOf(a, b), db.pageDao().listByNote(note.id))
        assertEquals(listOf(back, front), db.elementDao().listByPage(a.id))

        val updated = back.copy(elementJson = back.elementJson.replace("Back", "Changed"), revision = 1L)
        assertEquals(1, db.elementDao().update(updated))
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        assertEquals(listOf(a, b), db.pageDao().listByNote(note.id))
        assertEquals(listOf(updated, front), db.elementDao().listByPage(a.id))
        assertEquals(front, db.elementDao().findById(front.id))
        assertEquals(2, db.elementDao().countByPage(a.id))
    }

    @Test fun ownershipAndUniqueOrderConstraintsRejectInvalidWrites() = runBlocking {
        val note = createFixtureNote()
        val first = page(note.id, 0)
        db.pageDao().insert(first)
        assertWriteFails { db.pageDao().insert(page(UUID.randomUUID().toString(), 0)) }
        assertWriteFails { db.pageDao().insert(page(note.id, 0)) }
        assertWriteFails { db.elementDao().insert(element(UUID.randomUUID().toString(), 0)) }
        val valid = element(first.id, 0)
        db.elementDao().insert(valid)
        assertWriteFails { db.elementDao().insert(element(first.id, 0)) }
        assertEquals(listOf(first), db.pageDao().listByNote(note.id))
        assertEquals(listOf(valid), db.elementDao().listByPage(first.id))
        // RESTRICT prevents detached children if a caller bypasses repositories.
        assertWriteFails { db.pageDao().deleteById(first.id) }
        assertEquals(listOf(valid), db.elementDao().listByPage(first.id))
    }

    private suspend fun createFixtureNote(): NoteEntity {
        val notebookId = UUID.randomUUID().toString()
        db.notebookDao().insert(NotebookEntity(
            id = notebookId, parentFolderId = null, name = "Test notebook",
            sortOrder = 0, createdAtMillis = 100, updatedAtMillis = 100,
        ))
        return NoteEntity(
            id = UUID.randomUUID().toString(), notebookId = notebookId,
            title = "Test note", createdAtMillis = 100, updatedAtMillis = 100,
        ).also { db.noteDao().insert(it) }
    }

    private fun page(noteId: String, order: Long) = PageEntity(
        id = UUID.randomUUID().toString(), noteId = noteId, orderIndex = order,
        width = 612.0, height = 792.0,
        backgroundJson = """{"type":"blank","color":"#FFFFFF"}""", revision = 0,
    )

    private fun element(pageId: String, order: Long, text: String = "Text"): ElementEntity {
        val id = UUID.randomUUID().toString()
        return ElementEntity(
            id = id, pageId = pageId, orderIndex = order, kind = "text",
            elementJson = """{"id":"$id","type":"text","transform":[1,0,0,1,0,0],"locked":false,"payload":{"text":"$text","frame":{"x":0,"y":0,"width":100,"height":24},"style":{"fontFamily":"sans","fontSize":12,"color":"#202020","bold":false,"italic":false,"underline":false,"alignment":"left"}}}""",
            revision = 0,
        )
    }

    private suspend fun assertWriteFails(action: suspend () -> Unit) {
        var rejected = false
        try {
            action()
        } catch (expected: Exception) {
            rejected = true
        }
        assertTrue("Expected foreign-key/unique constraint failure", rejected)
    }
}
