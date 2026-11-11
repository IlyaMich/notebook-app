package com.notebook.app.data.assets

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.entity.ElementAssetReferenceEntity
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 C3-A2-I3-D-A: two-handle, isolated Room transaction research only.
 *
 * Not an export snapshot API or production concurrency guarantee. The only database
 * is a newly generated test-local file, never library.sqlite. All asset metadata is
 * synthetic; no image files, managed originals, external processes, or production
 * ownership gate changes are involved. No sleeps, polling for timing, or external
 * process termination. Compilation and device execution require separate approval.
 */
@RunWith(AndroidJUnit4::class)
class ExportSnapshotResearchConcurrencyAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var reader: AppDatabase
    private lateinit var writer: AppDatabase

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "sto08-c3a2-concurrency-${UUID.randomUUID()}.sqlite"
        check(databaseName.matches(Regex("sto08-c3a2-concurrency-[0-9a-f-]{36}\\.sqlite")))
        check(databaseName != AppDatabaseProvider.DATABASE_NAME)
        check(!context.getDatabasePath(databaseName).exists())
        reader = AppDatabaseProvider.openIsolated(context, databaseName)
        writer = AppDatabaseProvider.openIsolated(context, databaseName)
    }

    @After fun tearDown() {
        if (::reader.isInitialized) reader.close()
        if (::writer.isInitialized) writer.close()
        if (::context.isInitialized && ::databaseName.isInitialized) {
            check(databaseName.matches(Regex("sto08-c3a2-concurrency-[0-9a-f-]{36}\\.sqlite")))
            check(databaseName != AppDatabaseProvider.DATABASE_NAME)
            assertTrue("Isolated test database removal failed", context.deleteDatabase(databaseName))
        }
    }

    private fun id(): String = UUID.randomUUID().toString()
    private val blank = """{"type":"blank","color":"#ffffff"}"""

    private fun imageJson(elementId: String, assetId: String): String =
        """{"id":"$elementId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""

    private data class Fixture(
        val noteId: String,
        val pageId: String,
        val elementId: String,
        val firstAssetId: String,
        val nextAssetId: String,
    )

    private suspend fun createAsset(db: AppDatabase): String {
        val assetId = id()
        val sha = (id() + id()).replace("-", "")
        db.assetDao().insert(AssetEntity(
            id = assetId,
            sha256 = sha,
            mimeType = "image/png",
            byteSize = 10L,
            relativePath = "assets/${sha.take(2)}/${sha.substring(2, 4)}/$sha.png",
            state = "finalized",
        ))
        return assetId
    }

    private suspend fun fixture(): Fixture = writer.withTransaction {
        val notebookId = id()
        val noteId = id()
        val pageId = id()
        val elementId = id()
        val initial = createAsset(writer)
        val next = createAsset(writer)
        writer.notebookDao().insert(NotebookEntity(
            id = notebookId,
            parentFolderId = null,
            name = "C3-A2 concurrency fixture",
            sortOrder = 0L,
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        ))
        writer.noteDao().insert(NoteEntity(
            id = noteId,
            notebookId = notebookId,
            title = "Isolated research note",
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
            revision = 1L,
        ))
        writer.pageDao().insert(PageEntity(
            id = pageId,
            noteId = noteId,
            orderIndex = 0L,
            width = 612.0,
            height = 792.0,
            backgroundJson = blank,
            revision = 1L,
        ))
        writer.elementDao().insert(ElementEntity(
            id = elementId,
            pageId = pageId,
            orderIndex = 0L,
            kind = "image",
            elementJson = imageJson(elementId, initial),
            revision = 1L,
        ))
        writer.assetOwnershipDao().insertElementReference(
            ElementAssetReferenceEntity(elementId, initial)
        )
        Fixture(noteId, pageId, elementId, initial, next)
    }

    private data class Observation(
        val noteRevision: Long,
        val pageRevision: Long,
        val elementRevision: Long,
        val canonicalAssetId: String,
        val edgeAssetId: String,
        val metadataAssetId: String,
    )

    /** Caller must provide the surrounding Room transaction. */
    private suspend fun observeInTransaction(db: AppDatabase, f: Fixture): Observation {
        val note = requireNotNull(db.noteDao().findById(f.noteId))
        require(note.deletedAtMillis == null)
        val notebook = requireNotNull(db.notebookDao().findById(note.notebookId))
        require(notebook.deletedAtMillis == null && notebook.parentFolderId == null)
        val pages = db.pageDao().listByNote(note.id)
        require(pages.size == 1 && pages[0].id == f.pageId)
        val page = pages.single()
        require(page.backgroundJson == blank)
        require(db.assetOwnershipDao().findPageReference(page.id) == null)
        val elements = db.elementDao().listByPage(page.id)
        require(elements.size == 1 && elements[0].id == f.elementId)
        val element = elements.single()
        require(element.kind == "image")
        val finding = DocumentAssetReferenceInspector.element(element.elementJson)
        require(finding is DocumentAssetReferenceInspector.Finding.Reference) {
            "Image JSON is not a recognized direct reference"
        }
        val edge = requireNotNull(db.assetOwnershipDao().findElementReference(element.id))
        require(edge.assetId == finding.assetId) { "Canonical and indexed references disagree" }
        val asset = requireNotNull(db.assetDao().findById(edge.assetId))
        require(asset.state == "finalized" && asset.mimeType == "image/png")
        require(asset.byteSize == 10L && asset.sha256.matches(Regex("[0-9a-f]{64}")))
        require(asset.relativePath ==
            "assets/${asset.sha256.take(2)}/${asset.sha256.substring(2, 4)}/${asset.sha256}.png")
        require(page.revision in 0L..note.revision)
        require(element.revision in 0L..note.revision)
        return Observation(note.revision, page.revision, element.revision,
            finding.assetId, edge.assetId, asset.id)
    }

    private suspend fun snapshot(db: AppDatabase, f: Fixture): Observation =
        db.withTransaction { observeInTransaction(db, f) }

    /** Atomically replace owner JSON, typed edge and note revision; no real media bytes. */
    private suspend fun replaceInTransaction(f: Fixture) {
        val note = requireNotNull(writer.noteDao().findById(f.noteId))
        val element = requireNotNull(writer.elementDao().findById(f.elementId))
        check(note.revision == 1L)
        check(element.revision == 1L)
        check(writer.elementDao().update(element.copy(
            elementJson = imageJson(f.elementId, f.nextAssetId),
            revision = 2L,
        )) == 1)
        check(writer.assetOwnershipDao().changeElementAssetIfMatches(
            f.elementId, f.firstAssetId, f.nextAssetId
        ) == 1)
        check(writer.noteDao().update(note.copy(revision = 2L, updatedAtMillis = 2L)) == 1)
    }

    private fun assertVersion(observation: Observation, revision: Long, assetId: String) {
        assertEquals(revision, observation.noteRevision)
        assertEquals(1L, observation.pageRevision)
        assertEquals(revision, observation.elementRevision)
        assertEquals(assetId, observation.canonicalAssetId)
        assertEquals(assetId, observation.edgeAssetId)
        assertEquals(assetId, observation.metadataAssetId)
    }

    @Test fun d01CommittedWriterIsFullyVisibleToSubsequentSnapshot() = runBlocking {
        val f = fixture()
        assertVersion(snapshot(reader, f), 1L, f.firstAssetId)
        writer.withTransaction { replaceInTransaction(f) }
        assertVersion(snapshot(reader, f), 2L, f.nextAssetId)
        assertEquals("UNVERIFIED", reader.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun d02OverlappingWriterCannotProduceMixedReaderSnapshot() = runBlocking {
        val f = fixture()
        val readerInside = CompletableDeferred<Unit>()
        val writerAttempted = CompletableDeferred<Unit>()
        val allowReaderFinish = CompletableDeferred<Unit>()
        val first = CompletableDeferred<Observation>()
        val second = CompletableDeferred<Observation>()

        val readJob = async(Dispatchers.IO) {
            reader.withTransaction {
                first.complete(observeInTransaction(reader, f))
                readerInside.complete(Unit)
                // The reader remains in the same transaction across this suspension.
                allowReaderFinish.await()
                second.complete(observeInTransaction(reader, f))
            }
        }
        try {
            withTimeout(10_000L) { readerInside.await() }
            val writeJob = async(Dispatchers.IO) {
                writerAttempted.complete(Unit)
                writer.withTransaction { replaceInTransaction(f) }
            }
            try {
                withTimeout(10_000L) { writerAttempted.await() }
                // No assertion that a writer must finish while a read transaction is held.
                // Depending on SQLite/Room scheduling, the writer may wait or commit.
                allowReaderFinish.complete(Unit)
                withTimeout(20_000L) { readJob.await() }
                withTimeout(20_000L) { writeJob.await() }
            } finally {
                allowReaderFinish.complete(Unit)
                writeJob.cancel()
            }
            assertVersion(first.await(), 1L, f.firstAssetId)
            assertVersion(second.await(), 1L, f.firstAssetId)
            assertVersion(snapshot(reader, f), 2L, f.nextAssetId)
            assertEquals("UNVERIFIED", reader.assetOwnershipDao().readFailClosedGateStatus())
        } finally {
            allowReaderFinish.complete(Unit)
            readJob.cancel()
        }
    }

    @Test fun d03RolledBackWriterNeverExposesPartialOwnerOrRevision() = runBlocking {
        val f = fixture()
        val before = snapshot(reader, f)
        val rolledBack = runCatching {
            writer.withTransaction {
                replaceInTransaction(f)
                error("Deliberate rollback of isolated transaction")
            }
        }.exceptionOrNull()
        assertTrue("Rollback trigger must throw", rolledBack is IllegalStateException)
        assertVersion(before, 1L, f.firstAssetId)
        assertVersion(snapshot(reader, f), 1L, f.firstAssetId)
        assertVersion(snapshot(writer, f), 1L, f.firstAssetId)
        assertFalse(reader.assetOwnershipDao().findElementReference(f.elementId)?.assetId == f.nextAssetId)
        assertEquals("UNVERIFIED", reader.assetOwnershipDao().readFailClosedGateStatus())
    }
}
