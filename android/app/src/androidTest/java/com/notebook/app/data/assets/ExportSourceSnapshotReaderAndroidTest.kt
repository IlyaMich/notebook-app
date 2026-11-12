package com.notebook.app.data.assets

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Metadata-only synthetic fixtures. No original bytes exist or are verified. */
@RunWith(AndroidJUnit4::class)
class ExportSourceSnapshotReaderAndroidTest {
    private val blank = """{"type":"blank","color":"#ffffff"}"""
    // Research fixture bounds only, not production export quotas.
    private val limits = SnapshotLimits(32, 2048, 16, 16L * 1024 * 1024,
        64L * 1024 * 1024, 8L * 1024 * 1024, 32, 32)
    private fun id() = UUID.randomUUID().toString()

    private inner class Fixture(val db: AppDatabase) {
        val bookId = id()
        val noteId = id()
        val pageId = id()
        suspend fun seed() {
            db.notebookDao().insert(NotebookEntity(bookId, null, "synthetic",
                sortOrder = 0, createdAtMillis = 1, updatedAtMillis = 2))
            db.noteDao().insert(NoteEntity(noteId, bookId, "synthetic title", 1, 2,
                lastOpenedAtMillis = 2, revision = 3))
            db.pageDao().insert(PageEntity(pageId, noteId, 0, 612.0, 792.0, blank, 1))
        }
        suspend fun capture(policy: SnapshotLimits = limits, note: String = noteId) =
            ExportSourceSnapshotReader(db).capture(SnapshotRequest(note, policy))
        suspend fun complete(policy: SnapshotLimits = limits): SnapshotResult.Complete {
            val result = capture(policy)
            assertTrue(result is SnapshotResult.Complete)
            return result as SnapshotResult.Complete
        }
        suspend fun rejects(reason: SnapshotRejection, policy: SnapshotLimits = limits) {
            assertEquals(SnapshotResult.Rejected(reason), capture(policy))
        }
        suspend fun asset(mime: String = "image/png", bytes: Long = 10,
                          state: String = "finalized", path: String? = null,
                          hash: String = (id() + id()).replace("-", "")): String {
            val assetId = id()
            val ext = if (mime == "image/jpeg") "jpg" else "png"
            db.assetDao().insert(AssetEntity(assetId, hash, mime, bytes,
                path ?: "assets/${hash.take(2)}/${hash.substring(2, 4)}/$hash.$ext", state))
            return assetId
        }
        suspend fun element(assetId: String? = null, edge: String? = assetId,
                            order: Long? = null,
                            revision: Long = 2): ElementEntity {
            val eid = id()
            val raw = if (assetId == null) text(eid) else image(eid, assetId)
            val actualOrder = order ?: db.elementDao().countByPage(pageId).toLong()
            val row = ElementEntity(eid, pageId, actualOrder,
                if (assetId == null) "text" else "image", raw, revision)
            db.elementDao().insert(row)
            if (edge != null) db.assetOwnershipDao().insertElementReference(
                ElementAssetReferenceEntity(eid, edge))
            return row
        }
        suspend fun page() = requireNotNull(db.pageDao().findById(pageId))
    }

    /** Every invocation owns a fresh isolated Room v4 database, never library.sqlite. */
    private suspend fun fixture(action: suspend Fixture.() -> Unit) {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sto08-i4a-source-${id()}.sqlite"
        check(name.matches(Regex("sto08-i4a-source-[0-9a-f-]{36}\\.sqlite")))
        check(name != AppDatabaseProvider.DATABASE_NAME)
        check(!context.getDatabasePath(name).exists())
        val db = AppDatabaseProvider.openIsolated(context, name)
        try {
            Fixture(db).apply { seed() }.action()
        } finally {
            db.close()
            check(name.startsWith("sto08-i4a-source-") && name != AppDatabaseProvider.DATABASE_NAME)
            assertTrue(context.deleteDatabase(name))
        }
    }

    private fun text(eid: String) =
        """{ "id":"$eid","type":"text","transform":[1,0,0,1,0,0],"locked":false,"payload":{"text":"hello","frame":{"x":0,"y":0,"width":10,"height":10},"style":{"fontFamily":"sans","fontSize":12,"color":"#000000","bold":false,"italic":false,"underline":false,"alignment":"left"}}}"""
    private fun image(eid: String, assetId: String) =
        """{"id":"$eid","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""

    @Test fun s01MediaFreeNoteCompletes() = runBlocking { fixture {
        val s = complete()
        assertEquals(SnapshotNote(noteId, bookId, "synthetic title", 1, 2, 2, 3), s.note)
        assertEquals(listOf(SnapshotPage(pageId, noteId, 0, 612.0, 792.0,
            blank, 1, emptyList())), s.pages)
        assertEquals(emptyList<SnapshotImageOwner>(), s.imageOwners)
        assertEquals(emptyList<SnapshotAssetMetadata>(), s.assets)
        assertEquals(0L, s.totalEncodedSourceBytes)
        assertEquals(blank.toByteArray(Charsets.UTF_8).size.toLong(), s.canonicalJsonBytes)
    } }

    @Test fun s02OrderedContentPreservesExactStringsAndRevisions() = runBlocking { fixture {
        val a = element()
        val b = element(revision = 3)
        val secondPage = PageEntity(id(), noteId, 1, 100.0, 200.0,
            """{ "type":"grid","color":"#FFFFFF","patternColor":"#123456","spacing":12 }""", 3)
        db.pageDao().insert(secondPage)
        val s = complete()
        assertEquals(listOf(pageId, secondPage.id), s.pages.map { it.id })
        assertEquals(secondPage.backgroundJson, s.pages[1].canonicalBackground)
        assertEquals(3L, s.pages[1].revision)
        assertEquals(listOf(a, b).map { SnapshotElement(it.id, it.pageId, it.orderIndex,
            it.kind, it.elementJson, it.revision) }, s.pages[0].elements)
    } }

    @Test fun s03ImageWithExactEdgeCompletes() = runBlocking { fixture {
        val aid = asset()
        val e = element(aid)
        val s = complete()
        val row = requireNotNull(db.assetDao().findById(aid))
        assertEquals(listOf(SnapshotImageOwner(noteId, pageId, e.id, aid)), s.imageOwners)
        assertEquals(listOf(SnapshotAssetMetadata(aid, row.sha256, row.mimeType,
            row.byteSize, row.relativePath, row.state)), s.assets)
        assertEquals(e.elementJson, s.pages.single().elements.single().canonicalEnvelope)
        assertEquals(10L, s.totalEncodedSourceBytes)
    } }

    @Test fun s04SharedAssetRetainsBothOwners() = runBlocking { fixture {
        val aid = asset("image/jpeg")
        val a = element(aid); val b = element(aid)
        val s = complete()
        assertEquals(listOf(a, b).map { SnapshotImageOwner(noteId, pageId, it.id, aid) },
            s.imageOwners)
        assertEquals(listOf(aid), s.assets.map { it.id })
        assertEquals(10L, s.totalEncodedSourceBytes)
    } }

    @Test fun s05MissingImageEdgeRejected() = runBlocking { fixture {
        element(asset(), edge = null)
        rejects(SnapshotRejection.OWNER_EDGE_MISMATCH)
    } }
    @Test fun s06MismatchedImageEdgeRejected() = runBlocking { fixture {
        element(asset(), edge = asset())
        rejects(SnapshotRejection.OWNER_EDGE_MISMATCH)
    } }
    @Test fun s07PhantomElementEdgeRejected() = runBlocking { fixture {
        element(edge = asset())
        rejects(SnapshotRejection.OWNER_EDGE_MISMATCH)
    } }
    @Test fun s08PhantomPageEdgeRejected() = runBlocking { fixture {
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(pageId, asset()))
        rejects(SnapshotRejection.OWNER_EDGE_MISMATCH)
    } }

    @Test fun s09TrashedNoteAndNotebookRejected() = runBlocking {
        fixture {
            val n = requireNotNull(db.noteDao().findById(noteId))
            assertEquals(1, db.noteDao().update(n.copy(deletedAtMillis = 4)))
            rejects(SnapshotRejection.INELIGIBLE_NOTE)
        }
        fixture {
            val b = requireNotNull(db.notebookDao().findById(bookId))
            assertEquals(1, db.notebookDao().update(b.copy(deletedAtMillis = 4)))
            rejects(SnapshotRejection.INELIGIBLE_NOTE)
        }
    }

    @Test fun s10FolderAncestryDeletedMissingAndCyclicRejected() = runBlocking {
        for (variant in 0..2) fixture {
            val fid = id()
            if (variant != 1) db.folderDao().insert(FolderEntity(fid, null, "synthetic",
                0, 1, 2, deletedAtMillis = if (variant == 0) 4 else null))
            // Test-local corruption only, to exercise states normally prevented by FK.
            if (variant == 1) db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=OFF")
            val b = requireNotNull(db.notebookDao().findById(bookId))
            assertEquals(1, db.notebookDao().update(b.copy(parentFolderId = fid)))
            if (variant == 2) {
                val childId = id()
                db.folderDao().insert(
                    FolderEntity(childId, fid, "cycle-child", 0, 1, 2)
                )
                val f = requireNotNull(db.folderDao().findById(fid))
                assertEquals(
                    1,
                    db.folderDao().update(f.copy(parentFolderId = childId))
                )
            }
            rejects(SnapshotRejection.INELIGIBLE_NOTE)
        }
    }

    @Test fun s11MalformedHiddenIdentityAndDuplicateJsonRejected() = runBlocking {
        for (variant in 0..4) fixture {
            val e = element()
            val raw = when (variant) {
                0 -> "{"
                1 -> e.elementJson.replace("\"text\":\"hello\"",
                    "\"text\":\"hello\",\"assetId\":\"${id()}\"")
                2 -> text(id())
                3 -> e.elementJson.replace("\"locked\":false", "\"locked\":false,\"locked\":true")
                else -> e.elementJson + " trailing"
            }
            assertEquals(1, db.elementDao().update(e.copy(elementJson = raw)))
            rejects(SnapshotRejection.INVALID_DOCUMENT_STRUCTURE)
        }
    }

    @Test fun s12BadOrderingAndRevisionsRejected() = runBlocking {
        for (variant in 0..3) fixture {
            when (variant) {
                0 -> db.pageDao().update(page().copy(orderIndex = 1))
                1 -> element(order = 1)
                2 -> db.pageDao().update(page().copy(revision = 4))
                else -> element(revision = -1)
            }
            rejects(SnapshotRejection.INVALID_DOCUMENT_STRUCTURE)
        }
    }

    @Test fun s13InvalidImageMetadataRejected() = runBlocking {
        for (variant in 0..4) fixture {
            val aid = when (variant) {
                0 -> asset(bytes = 0)
                1 -> asset(state = "staged")
                2 -> asset(path = "../private.png")
                3 -> asset(hash = "A".repeat(64))
                else -> asset(path = "assets/wrong.jpg")
            }
            element(aid)
            rejects(SnapshotRejection.ASSET_METADATA_INVALID)
        }
    }

    @Test fun s14PdfPageExplicitlyUnsupported() = runBlocking { fixture {
        val raw = """{"type":"pdfPage","assetId":"${id()}","pageIndex":0,"displayBox":"crop"}"""
        assertEquals(1, db.pageDao().update(page().copy(backgroundJson = raw)))
        rejects(SnapshotRejection.UNSUPPORTED_MEDIA)
    } }

    @Test fun s15ResourceBoundariesAcceptedAndAboveRejected() = runBlocking { fixture {
        val aid = asset()
        val e = element(aid)
        val bytes = (blank.toByteArray(Charsets.UTF_8).size +
            e.elementJson.toByteArray(Charsets.UTF_8).size).toLong()
        val exact = limits.copy(maxPages = 1, maxElements = 1, maxDistinctAssets = 1,
            maxAssetBytes = 10, maxTotalAssetBytes = 10, maxCanonicalJsonBytes = bytes)
        val s = complete(exact)
        assertEquals(bytes, s.canonicalJsonBytes)
        assertEquals(10L, s.totalEncodedSourceBytes)
        for (policy in listOf(exact.copy(maxElements = 0),
            exact.copy(maxDistinctAssets = 0), exact.copy(maxAssetBytes = 9),
            exact.copy(maxTotalAssetBytes = 9),
            exact.copy(maxCanonicalJsonBytes = bytes - 1))) {
            rejects(SnapshotRejection.RESOURCE_LIMIT_EXCEEDED, policy)
        }
        db.pageDao().insert(page().copy(id = id(), orderIndex = 1))
        rejects(SnapshotRejection.RESOURCE_LIMIT_EXCEEDED, exact)
    } }

    @Test fun s16LocalSuccessLeavesGlobalGateUnverified() = runBlocking { fixture {
        val aid = asset(); val e = element(aid)
        val beforeNote = db.noteDao().findById(noteId)
        val beforePage = page()
        val beforeAsset = db.assetDao().findById(aid)
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        complete()
        assertEquals(beforeNote, db.noteDao().findById(noteId))
        assertEquals(beforePage, page())
        assertEquals(e, db.elementDao().findById(e.id))
        assertEquals(beforeAsset, db.assetDao().findById(aid))
        assertEquals(ElementAssetReferenceEntity(e.id, aid),
            db.assetOwnershipDao().findElementReference(e.id))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    } }

    @Test fun s17InvalidRequestAndMissingNoteAreTyped() = runBlocking { fixture {
        assertEquals(SnapshotResult.Rejected(SnapshotRejection.INVALID_REQUEST), capture(note = "bad"))
        assertEquals(SnapshotResult.Rejected(SnapshotRejection.NOTE_NOT_FOUND), capture(note = id()))
        rejects(SnapshotRejection.INVALID_REQUEST, limits.copy(maxPages = 0))
        rejects(SnapshotRejection.INVALID_REQUEST, limits.copy(maxJsonDepth = 65))
    } }

    @Test fun s18InvalidNonMediaSemanticsAndImageCropRejected() = runBlocking {
        for (variant in 0..2) fixture {
            val e = if (variant == 2) element(asset()) else element()
            val raw = when (variant) {
                0 -> e.elementJson.replace("\"fontSize\":12", "\"fontSize\":0")
                1 -> e.elementJson.replace("[1,0,0,1,0,0]", "[0,0,0,0,0,0]")
                else -> e.elementJson.replace("\"opacity\":1",
                    "\"opacity\":1,\"crop\":{\"x\":0.9,\"y\":0,\"width\":1,\"height\":1}")
            }
            assertEquals(1, db.elementDao().update(e.copy(elementJson = raw)))
            rejects(SnapshotRejection.INVALID_DOCUMENT_STRUCTURE)
        }
    }

    @Test fun s19FolderDepthIsBoundedAndActiveAncestryAccepted() = runBlocking { fixture {
        val fid = id()
        db.folderDao().insert(FolderEntity(fid, null, "synthetic", 0, 1, 2))
        val b = requireNotNull(db.notebookDao().findById(bookId))
        assertEquals(1, db.notebookDao().update(b.copy(parentFolderId = fid)))
        assertEquals(noteId, complete(limits.copy(maxFolderDepth = 1)).note.id)
        rejects(SnapshotRejection.RESOURCE_LIMIT_EXCEEDED, limits.copy(maxFolderDepth = 0))
    } }

    @Test fun s20MissingMetadataRejected() = runBlocking { fixture {
        val aid = id()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=OFF")
        element(aid)
        rejects(SnapshotRejection.ASSET_METADATA_MISSING)
    } }
    /**
     * I4-A-2F test-only dual-handle fixture. Never touches library.sqlite.
     * A timed-out worker is NOT proof of a stopped Room operation: retain the
     * isolated DB and handles instead of closing/deleting them unsafely.
     */
    private suspend fun twoHandleFixture(
        action: suspend (Fixture, AppDatabase, () -> Unit) -> Unit,
    ) {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sto08-i4a-2f-${id()}.sqlite"
        check(name.matches(Regex("sto08-i4a-2f-[0-9a-f-]{36}\\.sqlite")))
        check(name != AppDatabaseProvider.DATABASE_NAME)
        check(!context.getDatabasePath(name).exists())
        val readerDb = AppDatabaseProvider.openIsolated(context, name)
        var writerDb: AppDatabase? = null
        var unsafeCleanup = false
        try {
            writerDb = AppDatabaseProvider.openIsolated(context, name)
            val f = Fixture(readerDb)
            f.seed()
            action(f, requireNotNull(writerDb), { unsafeCleanup = true })
        } finally {
            if (unsafeCleanup) {
                // Fail closed: keep isolated file/handles for investigation.
                throw AssertionError("I4-A-2F Room worker not confirmed stopped; DB retained: $name")
            }
            writerDb?.close()
            readerDb.close()
            check(name.startsWith("sto08-i4a-2f-") && name != AppDatabaseProvider.DATABASE_NAME)
            assertTrue(context.deleteDatabase(name))
        }
    }

    /** Cooperative shutdown with a bound; never mistake timeout for safe cleanup. */
    private suspend fun stopWorker(
        job: Job, label: String, markCleanupUnsafe: () -> Unit,
    ) {
        job.cancel()
        val stopped = try {
            withContext(NonCancellable) {
                withTimeoutOrNull(10_000L) {
                    job.join()
                    true
                } ?: false
            }
        } catch (failure: Throwable) {
            markCleanupUnsafe()
            throw AssertionError("$label: worker completion unconfirmed", failure)
        }
        if (!stopped || !job.isCompleted) {
            markCleanupUnsafe()
            throw AssertionError("$label: worker still active; isolated DB retained")
        }
    }

    @Test fun s21CancelledQueuedCapturePropagatesAndHandleRecovers() = runBlocking {
        twoHandleFixture { f, _, markCleanupUnsafe ->
            supervisorScope {
                val holderEntered = CompletableDeferred<Unit>()
                val releaseHolder = CompletableDeferred<Unit>()
                // Hold the SAME Room handle's transaction queue before capture.
                val holder = async(Dispatchers.IO) {
                    f.db.withTransaction {
                        holderEntered.complete(Unit)
                        releaseHolder.await()
                    }
                }
                var pending: Deferred<SnapshotResult>? = null
                try {
                    withTimeout(10_000L) { holderEntered.await() }
                    val captureJob = async(start = CoroutineStart.UNDISPATCHED) {
                        f.capture()
                    }
                    pending = captureJob
                    assertFalse("Queued capture unexpectedly completed", captureJob.isCompleted)
                    stopWorker(captureJob, "s21 cancelled capture", markCleanupUnsafe)
                    assertTrue("Capture must be cancelled", captureJob.isCancelled)
                    val outcome = runCatching { captureJob.await() }.exceptionOrNull()
                    assertTrue("Capture must propagate CancellationException",
                        outcome is CancellationException)
                } finally {
                    pending?.let { stopWorker(it, "s21 pending capture", markCleanupUnsafe) }
                    // Cancel first; even an assertion failure must not hold Room open.
                    releaseHolder.complete(Unit)
                    stopWorker(holder, "s21 transaction holder", markCleanupUnsafe)
                }
                assertEquals(f.noteId, f.complete().note.id)
                assertEquals("UNVERIFIED", f.db.assetOwnershipDao().readFailClosedGateStatus())
            }
        }
    }

    @Test fun s22OverlappingWriterYieldsOnlyCoherentCommittedCapture() = runBlocking {
        twoHandleFixture { f, writer, markCleanupUnsafe ->
            val oldAssetId = f.asset()
            val newAssetId = f.asset()
            val oldElement = f.element(oldAssetId)
            val before = f.complete()
            assertEquals(3L, before.note.revision)
            assertEquals(oldAssetId, before.imageOwners.single().assetId)
            supervisorScope {
                val changesWritten = CompletableDeferred<Unit>()
                val allowCommit = CompletableDeferred<Unit>()
                val readerRequested = CompletableDeferred<Unit>()
                val writerJob = async(Dispatchers.IO) {
                    writer.withTransaction {
                        val note = requireNotNull(writer.noteDao().findById(f.noteId))
                        val element = requireNotNull(writer.elementDao().findById(oldElement.id))
                        assertEquals(3L, note.revision)
                        assertEquals(2L, element.revision)
                        assertEquals(1, writer.elementDao().update(element.copy(
                            elementJson = image(element.id, newAssetId), revision = 4L)))
                        assertEquals(1, writer.assetOwnershipDao().changeElementAssetIfMatches(
                            element.id, oldAssetId, newAssetId))
                        assertEquals(1, writer.noteDao().update(note.copy(
                            revision = 4L, updatedAtMillis = 4L)))
                        // All writes occurred; commit is still blocked by this gate.
                        changesWritten.complete(Unit)
                        allowCommit.await()
                    }
                }
                var readJob: Deferred<SnapshotResult>? = null
                try {
                    withTimeout(10_000L) { changesWritten.await() }
                    val pending = async(start = CoroutineStart.UNDISPATCHED) {
                        // Request signal is NOT proof of SQLite transaction entry.
                        readerRequested.complete(Unit)
                        f.capture()
                    }
                    readJob = pending
                    withTimeout(10_000L) { readerRequested.await() }
                    allowCommit.complete(Unit)
                    withTimeout(20_000L) { writerJob.await() }
                    val observed = withTimeout(20_000L) { pending.await() }
                    val after = f.complete()
                    assertEquals(4L, after.note.revision)
                    assertEquals(newAssetId, after.imageOwners.single().assetId)
                    assertTrue("Snapshot must be one whole committed version",
                        observed == before || observed == after)
                    assertEquals("UNVERIFIED",
                        f.db.assetOwnershipDao().readFailClosedGateStatus())
                } finally {
                    // On abnormal exit cancel BEFORE unlocking a possible commit.
                    writerJob.cancel()
                    allowCommit.complete(Unit)
                    stopWorker(writerJob, "s22 writer", markCleanupUnsafe)
                    readJob?.let { stopWorker(it, "s22 capture", markCleanupUnsafe) }
                }
            }
        }
    }
}
