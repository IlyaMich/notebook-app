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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 C3-A2-I3-E, Step 153 safety refinement (E01, E03, E04 and E05).
 *
 * Isolated Room scheduling/cancellation research, NOT proof of SQLite lock
 * contention or native SQLite interruption. No production DB, real asset bytes,
 * asset reclamation, export activation, or ownership-gate promotion.
 * Compilation and instrumentation are separate authorization gates.
 */
@RunWith(AndroidJUnit4::class)
class ExportSnapshotResearchCancellationAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var reader: AppDatabase
    private lateinit var writer: AppDatabase

    // Do not close/delete a test DB if cancellation or bounded Room work
    // leaves a coroutine/connection with an unconfirmed completion state.
    private var databaseCleanupUnsafe = false

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "sto08-c3a2-i3e-${UUID.randomUUID()}.sqlite"
        check(databaseName.matches(Regex("sto08-c3a2-i3e-[0-9a-f-]{36}\\.sqlite")))
        check(databaseName != AppDatabaseProvider.DATABASE_NAME)
        check(!context.getDatabasePath(databaseName).exists())
        reader = AppDatabaseProvider.openIsolated(context, databaseName)
        writer = AppDatabaseProvider.openIsolated(context, databaseName)
    }

    @After fun tearDown() {
        check(!databaseCleanupUnsafe) {
            "Unconfirmed Room worker/operation; isolated database deliberately retained"
        }
        if (::reader.isInitialized) reader.close()
        if (::writer.isInitialized) writer.close()
        if (::context.isInitialized && ::databaseName.isInitialized) {
            check(databaseName.matches(Regex("sto08-c3a2-i3e-[0-9a-f-]{36}\\.sqlite")))
            check(databaseName != AppDatabaseProvider.DATABASE_NAME)
            assertTrue("Isolated research DB cleanup failed", context.deleteDatabase(databaseName))
        }
    }

    /**
     * Attempt bounded cooperative shutdown even when the calling test's parent
     * coroutine is cancelled. A timeout NEVER certifies that the Room handle is
     * idle: fail closed and retain the isolated database for diagnosis instead
     * of closing or deleting resources that a worker might still be using.
     * This helper does not interrupt a blocked native SQLite operation.
     */
    private suspend fun cancelAndJoinBounded(job: Job, label: String) {
        job.cancel()
        val completed = try {
            withContext(NonCancellable) {
                withTimeoutOrNull(10_000L) {
                    job.join()
                    true
                } ?: false
            }
        } catch (failure: Throwable) {
            databaseCleanupUnsafe = true
            throw AssertionError("$label: cleanup completion unconfirmed", failure)
        }
        if (!completed || !job.isCompleted) {
            databaseCleanupUnsafe = true
            throw AssertionError("$label: worker did not terminate; isolated DB retained")
        }
    }

    /** Bound top-level Room operations, including post-cancellation progress. */
    private suspend fun <T> boundedRoom(label: String, action: suspend () -> T): T =
        try {
            withTimeout(20_000L) { action() }
        } catch (timeout: TimeoutCancellationException) {
            // A timeout is not proof that all underlying native work stopped.
            databaseCleanupUnsafe = true
            throw AssertionError("$label: timed out; isolated DB retained", timeout)
        }

    private fun id(): String = UUID.randomUUID().toString()
    private val blank = """{"type":"blank","color":"#ffffff"}"""

    private fun imageJson(elementId: String, assetId: String) =
        """{"id":"$elementId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""

    private data class Fixture(
        val noteId: String,
        val pageId: String,
        val elementId: String,
        val oldAssetId: String,
        val newAssetId: String,
    )

    private suspend fun addAsset(): String {
        val assetId = id()
        val hash = (id() + id()).replace("-", "")
        writer.assetDao().insert(AssetEntity(
            id = assetId,
            sha256 = hash,
            mimeType = "image/png",
            byteSize = 10L,
            relativePath = "assets/${hash.take(2)}/${hash.substring(2, 4)}/$hash.png",
            state = "finalized",
        ))
        return assetId
    }

    private suspend fun fixture(): Fixture = boundedRoom("fixture") {
        writer.withTransaction {
            val notebookId = id()
            val noteId = id()
            val pageId = id()
            val elementId = id()
            val oldAssetId = addAsset()
            val newAssetId = addAsset()
            writer.notebookDao().insert(NotebookEntity(
                id = notebookId, parentFolderId = null, name = "I3-E isolated fixture",
                sortOrder = 0L, createdAtMillis = 1L, updatedAtMillis = 1L,
            ))
            writer.noteDao().insert(NoteEntity(
                id = noteId, notebookId = notebookId, title = "Scheduling fixture",
                createdAtMillis = 1L, updatedAtMillis = 1L, revision = 1L,
            ))
            writer.pageDao().insert(PageEntity(
                id = pageId, noteId = noteId, orderIndex = 0L,
                width = 612.0, height = 792.0, backgroundJson = blank, revision = 1L,
            ))
            writer.elementDao().insert(ElementEntity(
                id = elementId, pageId = pageId, orderIndex = 0L,
                kind = "image", elementJson = imageJson(elementId, oldAssetId), revision = 1L,
            ))
            writer.assetOwnershipDao().insertElementReference(
                ElementAssetReferenceEntity(elementId, oldAssetId)
            )
            Fixture(noteId, pageId, elementId, oldAssetId, newAssetId)
        }
    }

    private data class Observation(val revision: Long, val assetId: String)

    /** Always called inside an already entered Room transaction. */
    private suspend fun observe(db: AppDatabase, f: Fixture): Observation {
        val note = requireNotNull(db.noteDao().findById(f.noteId))
        check(note.deletedAtMillis == null)
        val notebook = requireNotNull(db.notebookDao().findById(note.notebookId))
        check(notebook.deletedAtMillis == null && notebook.parentFolderId == null)
        val page = db.pageDao().listByNote(f.noteId).single()
        check(page.id == f.pageId && page.backgroundJson == blank)
        check(page.revision == 1L && page.revision <= note.revision)
        check(db.assetOwnershipDao().findPageReference(f.pageId) == null)
        val element = db.elementDao().listByPage(f.pageId).single()
        check(element.id == f.elementId && element.kind == "image")
        check(element.revision == note.revision)
        val finding = DocumentAssetReferenceInspector.element(element.elementJson)
        check(finding is DocumentAssetReferenceInspector.Finding.Reference)
        val edge = requireNotNull(db.assetOwnershipDao().findElementReference(f.elementId))
        check(edge.assetId == finding.assetId)
        val asset = requireNotNull(db.assetDao().findById(edge.assetId))
        check(asset.id == finding.assetId && asset.state == "finalized")
        check(asset.mimeType == "image/png" && asset.byteSize == 10L)
        check(asset.sha256.matches(Regex("[0-9a-f]{64}")))
        check(asset.relativePath ==
            "assets/${asset.sha256.take(2)}/${asset.sha256.substring(2, 4)}/${asset.sha256}.png")
        return Observation(note.revision, finding.assetId)
    }

    private suspend fun snapshot(db: AppDatabase, f: Fixture): Observation =
        boundedRoom("snapshot") { db.withTransaction { observe(db, f) } }

    private suspend fun gateStatus(db: AppDatabase): String =
        boundedRoom("ownership gate read") {
            db.assetOwnershipDao().readFailClosedGateStatus()
        }

    /** Caller owns writer.withTransaction; mutation is a single atomic revision. */
    private suspend fun replaceInTransaction(f: Fixture) {
        val note = requireNotNull(writer.noteDao().findById(f.noteId))
        val element = requireNotNull(writer.elementDao().findById(f.elementId))
        check(note.revision == 1L && element.revision == 1L)
        check(writer.elementDao().update(element.copy(
            elementJson = imageJson(f.elementId, f.newAssetId), revision = 2L,
        )) == 1)
        check(writer.assetOwnershipDao().changeElementAssetIfMatches(
            f.elementId, f.oldAssetId, f.newAssetId,
        ) == 1)
        check(writer.noteDao().update(note.copy(
            revision = 2L, updatedAtMillis = 2L,
        )) == 1)
    }

    @Test fun e01RequestAndEntryAreDistinctFromObservedSnapshot() = runBlocking {
        val f = fixture()
        val readerInside = CompletableDeferred<Unit>()
        val allowReaderFinish = CompletableDeferred<Unit>()
        val writerRequested = CompletableDeferred<Unit>()
        val writerEntered = CompletableDeferred<Unit>()
        val first = CompletableDeferred<Observation>()
        val second = CompletableDeferred<Observation>()

        val readJob = async(Dispatchers.IO) {
            reader.withTransaction {
                first.complete(observe(reader, f))
                readerInside.complete(Unit)
                allowReaderFinish.await()
                second.complete(observe(reader, f))
            }
        }
        try {
            withTimeout(10_000L) { readerInside.await() }
            val writeJob = async(Dispatchers.IO) {
                // Request notification happens BEFORE Room transaction entry.
                writerRequested.complete(Unit)
                writer.withTransaction {
                    // Entry notification happens INSIDE the Room transaction body.
                    writerEntered.complete(Unit)
                    replaceInTransaction(f)
                }
            }
            try {
                withTimeout(10_000L) { writerRequested.await() }
                // No claim that SQLite contention occurred or writer must wait.
                allowReaderFinish.complete(Unit)
                withTimeout(20_000L) { readJob.await() }
                withTimeout(20_000L) { writeJob.await() }
                withTimeout(10_000L) { writerEntered.await() }
            } finally {
                // Cancel before opening the reader gate on any failed assertion.
                writeJob.cancel()
                allowReaderFinish.complete(Unit)
                cancelAndJoinBounded(writeJob, "writer")
            }
            assertEquals(Observation(1L, f.oldAssetId), boundedRoom("E01 first observation") { first.await() })
            assertEquals(Observation(1L, f.oldAssetId), boundedRoom("E01 second observation") { second.await() })
            assertEquals(Observation(2L, f.newAssetId), snapshot(reader, f))
            assertEquals("UNVERIFIED", gateStatus(reader))
        } finally {
            readJob.cancel()
            allowReaderFinish.complete(Unit)
            cancelAndJoinBounded(readJob, "reader")
        }
    }

    @Test fun e03CancelledSameHandleRequestCannotWriteAndLaterWriterProgresses() = runBlocking {
        val f = fixture()
        val holderInside = CompletableDeferred<Unit>()
        val allowHolderFinish = CompletableDeferred<Unit>()
        val cancelledBodyEntered = AtomicBoolean(false)
        val requestIssued = AtomicBoolean(false)

        // Same Room handle deliberately separates scheduling from SQLite locks.
        val holder = async(Dispatchers.IO) {
            writer.withTransaction {
                check(observe(writer, f) == Observation(1L, f.oldAssetId))
                holderInside.complete(Unit)
                allowHolderFinish.await()
            }
        }
        try {
            withTimeout(10_000L) { holderInside.await() }
            // UNDISPATCHED runs the request marker before returning from async.
            // It does not prove Room queued the transaction, nor any SQLite lock.
            val pendingWriter = async(start = CoroutineStart.UNDISPATCHED) {
                requestIssued.set(true)
                writer.withTransaction {
                    cancelledBodyEntered.set(true)
                    replaceInTransaction(f)
                }
            }
            try {
                assertTrue("Request marker did not run", requestIssued.get())
                assertFalse("Same-handle transaction entered prematurely", cancelledBodyEntered.get())
                cancelAndJoinBounded(pendingWriter, "E03 pending writer")
                assertTrue("Pending transaction must be cancelled", pendingWriter.isCancelled)
                assertFalse("Cancelled writer body must not enter", cancelledBodyEntered.get())
            } finally {
                pendingWriter.cancel()
                allowHolderFinish.complete(Unit)
                cancelAndJoinBounded(pendingWriter, "pending writer")
            }
            withTimeout(20_000L) { holder.await() }
            assertEquals(Observation(1L, f.oldAssetId), snapshot(reader, f))
            boundedRoom("E03 subsequent writer") {
                writer.withTransaction { replaceInTransaction(f) }
            }
            assertEquals(Observation(2L, f.newAssetId), snapshot(reader, f))
            assertEquals("UNVERIFIED", gateStatus(reader))
        } finally {
            holder.cancel()
            allowHolderFinish.complete(Unit)
            cancelAndJoinBounded(holder, "holder")
        }
    }

    @Test fun e04CancellationAfterMutationRollsBackCanonicalAndIndexedState() = runBlocking {
        val f = fixture()
        val mutationApplied = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        val writeJob = async(Dispatchers.IO) {
            writer.withTransaction {
                // All three writes have completed when the signal is published,
                // but the Room transaction has NOT yet committed.
                replaceInTransaction(f)
                mutationApplied.complete(Unit)
                allowCommit.await()
            }
        }
        try {
            withTimeout(10_000L) { mutationApplied.await() }
            // A cancellation delivered at the suspended gate must roll back the
            // element JSON, typed edge and note revision as a single transaction.
            cancelAndJoinBounded(writeJob, "writer")
            assertTrue("Active writer must be cancelled", writeJob.isCancelled)
            assertEquals(Observation(1L, f.oldAssetId), snapshot(reader, f))
            assertEquals(Observation(1L, f.oldAssetId), snapshot(writer, f))
            assertEquals("UNVERIFIED", gateStatus(reader))
        } finally {
            // Cancel FIRST: a failed assertion must not accidentally commit.
            writeJob.cancel()
            allowCommit.complete(Unit)
            cancelAndJoinBounded(writeJob, "writer")
        }
    }

    @Test fun e05CancelledReaderReleasesHandleAndAllowsSubsequentWriter() = runBlocking {
        val f = fixture()
        val readerEntered = CompletableDeferred<Unit>()
        val allowReaderFinish = CompletableDeferred<Unit>()
        val readJob = async(Dispatchers.IO) {
            reader.withTransaction {
                check(observe(reader, f) == Observation(1L, f.oldAssetId))
                readerEntered.complete(Unit)
                allowReaderFinish.await()
            }
        }
        try {
            withTimeout(10_000L) { readerEntered.await() }
            cancelAndJoinBounded(readJob, "reader")
            assertTrue("Suspended reader must be cancelled", readJob.isCancelled)
            // A new transaction on the SAME handle must become available again.
            assertEquals(
                Observation(1L, f.oldAssetId),
                withTimeout(10_000L) { snapshot(reader, f) },
            )
            boundedRoom("E05 subsequent writer") {
                writer.withTransaction { replaceInTransaction(f) }
            }
            assertEquals(Observation(2L, f.newAssetId), snapshot(reader, f))
            assertEquals("UNVERIFIED", gateStatus(reader))
        } finally {
            readJob.cancel()
            allowReaderFinish.complete(Unit)
            cancelAndJoinBounded(readJob, "reader")
        }
    }
}
