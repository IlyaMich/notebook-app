package com.notebook.app.data.local

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.validation.CanonicalContentValidator
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-07 S03-B: opt-in, disposable-AVD-only baseline fixture.
 *
 * Unlike normal integration tests, this intentionally writes a named fixture
 * to the application's real `library.sqlite` so the actual Notebook UI can
 * display it. The host runner MUST verify the AVD identity before invocation.
 *
 * Never run this on a device containing real user data. With no sto07Phase
 * argument, both tests are skipped in ordinary CI/instrumentation suites.
 */
@RunWith(AndroidJUnit4::class)
class Sto07S03bDeviceBaselineTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val marker
        get() = context.getSharedPreferences("sto07-s03b-device-baseline", Context.MODE_PRIVATE)

    private fun phase(required: String) {
        assumeTrue(
            "S03-B device baseline requires its explicitly guarded host script",
            InstrumentationRegistry.getArguments().getString("sto07Phase") == required,
        )
    }

    private fun shapeEnvelope(id: String): String =
        """{"id":"$id","type":"shape","transform":[1,0,0,1,0,0],"locked":false,"payload":{"kind":"line","geometry":{"x1":12,"y1":20,"x2":40,"y2":60},"stroke":{"color":"#2255AA","width":2,"opacity":1},"fill":null}}"""

    private fun fixtureId(key: String): String =
        marker.getString(key, null) ?: error("S03-B fixture is missing marker $key")

    @Test
    fun createBaselineInDisposableAppDatabase(): Unit = runBlocking {
        phase("prepare")
        if (marker.contains("noteId")) {
            // Idempotent: never create duplicates if the script is run twice.
            validateBaseline()
            Log.i("STO07_S03B", "BASELINE_ALREADY_PRESENT")
            return@runBlocking
        }

        val notebookId = UUID.randomUUID().toString()
        val noteId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        val elementId = UUID.randomUUID().toString()
        val elementJson = shapeEnvelope(elementId)
        CanonicalContentValidator.element(elementJson)
        val white = """{"type":"blank","color":"#FFFFFF"}"""
        val now = System.currentTimeMillis()

        val db = AppDatabaseProvider.get(context)
        val sql = db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            sql.execSQL(
                """INSERT INTO notebooks(
                    id, parent_folder_id, name, sort_order,
                    system_role, created_at, updated_at
                ) VALUES (?, NULL, ?, 0, NULL, ?, ?)""",
                arrayOf<Any>(notebookId, "STO07 S03B Recovery", now, now),
            )
            sql.execSQL(
                """INSERT INTO notes(
                    id, notebook_id, title, created_at, updated_at, revision
                ) VALUES (?, ?, ?, ?, ?, 7)""",
                arrayOf<Any>(noteId, notebookId, "Baseline before low storage", now, now),
            )
            sql.execSQL(
                """INSERT INTO pages(
                    id, note_id, order_index, width, height,
                    background_json, revision
                ) VALUES (?, ?, 0, 595.276, 841.890, ?, 7)""",
                arrayOf(pageId, noteId, white),
            )
            sql.execSQL(
                """INSERT INTO elements(
                    id, page_id, order_index, kind, element_json, revision
                ) VALUES (?, ?, 0, 'shape', ?, 7)""",
                arrayOf(elementId, pageId, elementJson),
            )
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }

        val stored = RoomDocumentRepository(db).load(noteId)
        assertEquals(7L, stored.revision)
        assertEquals(pageId, stored.pages.single().id)
        assertEquals(elementId, stored.pages.single().elements.single().id)
        assertEquals(elementJson, db.elementDao().listByPage(pageId).single().elementJson)

        // commit(), rather than apply(), makes the fixture IDs durable before
        // the host force-stops the application for a new-process verification.
        assertTrue(
            "Could not persist S03-B fixture IDs",
            marker.edit()
                .putString("notebookId", notebookId)
                .putString("noteId", noteId)
                .putString("pageId", pageId)
                .putString("elementId", elementId)
                .commit(),
        )
        Log.i("STO07_S03B", "BASELINE_PREPARED revision=7 noteId=$noteId")
        Unit
    }

    @Test
    fun verifyBaselineAfterNewProcess(): Unit = runBlocking {
        phase("verify")
        validateBaseline()
        Log.i("STO07_S03B", "BASELINE_VERIFIED revision=7 noteId=${fixtureId("noteId")}")
        Unit
    }

    private suspend fun validateBaseline() {
        val db = AppDatabaseProvider.get(context)
        val notebookId = fixtureId("notebookId")
        val noteId = fixtureId("noteId")
        val pageId = fixtureId("pageId")
        val elementId = fixtureId("elementId")

        val notebook = db.notebookDao().findById(notebookId)
        assertNotNull("Notebook disappeared", notebook)
        assertEquals("STO07 S03B Recovery", notebook!!.name)

        val note = db.noteDao().findById(noteId)
        assertNotNull("Note disappeared", note)
        assertEquals(notebookId, note!!.notebookId)
        assertEquals("Baseline before low storage", note.title)
        assertEquals(7L, note.revision)

        val persisted = RoomDocumentRepository(db).load(noteId)
        assertEquals(7L, persisted.revision)
        assertEquals(1, persisted.pages.size)
        assertEquals(pageId, persisted.pages.single().id)
        assertEquals(7L, persisted.pages.single().revision)
        assertEquals("""{"type":"blank","color":"#FFFFFF"}""", persisted.pages.single().backgroundJson)
        val elements = persisted.pages.single().elements
        assertEquals(1, elements.size)
        assertEquals(elementId, elements.single().id)
        assertEquals(7L, elements.single().revision)
        assertEquals(shapeEnvelope(elementId), db.elementDao().listByPage(pageId).single().elementJson)
    }
}
