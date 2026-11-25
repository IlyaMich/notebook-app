package com.notebook.app.data.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D2J-A only: real isolated Room v7 + immutable app-private source/ZIP,
 * one-shot fake disposable provider, and read-only restart. No real app DB,
 * user picker, original GC, release, or external provider cleanup.
 */
@RunWith(AndroidJUnit4::class)
class ExportRoomBoundHandoffCoordinatorAndroidTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dbName: String
    private lateinit var root: File
    private lateinit var noteId: String
    private lateinit var pageId: String
    private val snap = SnapshotLimits(4, 16, 4, 100_000, 400_000, 100_000, 4, 16)
    private val copy = ExportCopyAdmissionLimits(4, 100_000, 400_000)
    private val stage = ExportPrivateStageLimits(4, 100_000, 400_000, 100_000, 12)

    private fun id() = UUID.randomUUID().toString()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Before fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "sto08-d2j-" + id() + ".sqlite"
        assertTrue(dbName != AppDatabaseProvider.DATABASE_NAME)
        root = File(context.filesDir, "sto08-d2j-disposable-" + id())
        assertTrue(root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, dbName)
        db.openHelper.writableDatabase

        val book = id()
        noteId = id()
        pageId = id()
        db.notebookDao().insert(NotebookEntity(
            book, null, "D2J synthetic book", sortOrder = 0L,
            createdAtMillis = 1L, updatedAtMillis = 1L,
        ))
        db.noteDao().insert(NoteEntity(
            noteId, book, "D2J synthetic note", 1L, 1L, revision = 2L,
        ))
        db.pageDao().insert(PageEntity(
            pageId, noteId, 0L, 600.0, 800.0,
            """{"type":"blank","color":"#FFFFFF"}""", 1L,
        ))
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::dbName.isInitialized) {
            assertTrue("Test database missing", context.deleteDatabase(dbName))
        }
        if (::root.isInitialized) {
            assertTrue(root.name.startsWith("sto08-d2j-disposable-"))
            assertTrue("Test-owned fixture cleanup failed", root.deleteRecursively())
        }
    }

    private fun service() = ExportRoomBoundHandoffCoordinator(root, db, snap, copy, stage) { 123L }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(6, 5, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(47, 109, 184))
            ByteArrayOutputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 95, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private suspend fun attachImage(): Pair<String, File> {
        val bytes = png()
        val sha = digest(bytes)
        val assetId = id()
        val relative = "assets/" + sha.take(2) + "/" + sha.substring(2, 4) + "/" + sha + ".png"
        val original = File(root, relative)
        assertTrue(original.parentFile!!.mkdirs())
        original.writeBytes(bytes)
        db.assetDao().insert(AssetEntity(
            assetId, sha, "image/png", bytes.size.toLong(), relative, "finalized",
        ))
        val element = id()
        val envelope = """{"id":"$element","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""
        db.elementDao().insert(ElementEntity(
            element, pageId, 0L, "image", envelope, 1L,
        ))
        db.assetOwnershipDao().insertElementReference(
            ElementAssetReferenceEntity(element, assetId),
        )
        return assetId to original
    }

    private fun prepared(result: ExportRoomBoundHandoffCoordinator.Preparation):
        ExportRoomBoundHandoffCoordinator.Preparation.AwaitingFreshUserDocumentUntrusted {
        assertTrue("Expected Room-bound untrusted archive: $result",
            result is ExportRoomBoundHandoffCoordinator.Preparation.AwaitingFreshUserDocumentUntrusted)
        return result as ExportRoomBoundHandoffCoordinator.Preparation.AwaitingFreshUserDocumentUntrusted
    }

    private suspend fun assertProtected(op: String, assetIds: List<String> = emptyList()) {
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        assertEquals("COMMITTED_PIN_SET", db.exportHoldAdmissionDao().findOperation(op)?.state)
        assertEquals(assetIds.sorted(), db.exportHoldAdmissionDao().listHolds(op).map { it.assetId })
        assertNotNull(db.exportHoldAdmissionDao().findSourceBinding(op))
    }

    private fun reopen() {
        db.close()
        db = AppDatabaseProvider.openIsolated(context, dbName)
    }

    private class DisposableDestination(
        override val destinationIdentitySha256: String = "d2".repeat(32),
        private val failOpen: Boolean = false,
        private val failReadback: Boolean = false,
    ) : ExportExternalDocumentDestination {
        private val stream = ByteArrayOutputStream()
        var openCount = 0
        var readCount = 0
        override fun openWriteOnce(): ExportExternalDocumentDestination.Sink {
            openCount++
            if (failOpen) throw IOException("Disposable test provider refuses write")
            return object : ExportExternalDocumentDestination.Sink {
                override val output = stream
                override fun syncOrThrow() = Unit
                override fun close() = Unit
            }
        }
        override fun openReadBack(): InputStream {
            readCount++
            if (failReadback) throw IOException("Disposable test provider refuses readback")
            return ByteArrayInputStream(stream.toByteArray())
        }
        fun bytes(): ByteArray = stream.toByteArray()
    }

    @Test fun mediaFreeNoteProducesRoomBoundPrivateArchiveWithoutProvider() = runBlocking {
        val prepared = prepared(service().prepareNew(noteId))
        assertEquals(noteId, prepared.noteId)
        assertEquals(2L, prepared.noteRevision)
        val zip = File(root, "retained-private-export-archives/${prepared.operationId}.notebook")
        assertEquals(prepared.archiveSha256, digest(zip.readBytes()))
        ZipFile(zip).use { assertNotNull(it.getEntry("manifest.json")) }
        assertProtected(prepared.operationId)
        assertEquals(ExportRoomBoundHandoffCoordinator.Recovery.NoAttemptRecordedUntrusted(
            prepared.operationId,
        ), service().inspectReadOnly(prepared.operationId))
    }

    @Test fun imageBackedNoteRetainsSourceAndPinsThroughRoomReopen() = runBlocking {
        val (asset, original) = attachImage()
        val expected = original.readBytes()
        val prepared = prepared(service().prepareNew(noteId))
        assertProtected(prepared.operationId, listOf(asset))
        assertArrayEquals(expected, original.readBytes())
        reopen()
        assertProtected(prepared.operationId, listOf(asset))
        assertEquals(ExportRoomBoundHandoffCoordinator.Recovery.NoAttemptRecordedUntrusted(
            prepared.operationId,
        ), service().inspectReadOnly(prepared.operationId))
        assertArrayEquals(expected, original.readBytes())
    }

    @Test fun invalidNoteIdRejectsWithoutCreatingNewOperation() = runBlocking {
        assertEquals(ExportRoomBoundHandoffCoordinator.Preparation.Rejected(
            SnapshotRejection.INVALID_REQUEST,
        ), service().prepareNew("not-a-uuid"))
        assertEquals(0, db.exportHoldAdmissionDao().listRecoveryHoldRows(1).size)
        assertEquals(0, db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM export_operations",
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun trashedNoteRejectedBeforeAnyHoldSet() = runBlocking {
        val original = db.noteDao().findById(noteId)!!
        assertEquals(1, db.noteDao().update(original.copy(deletedAtMillis = 22L)))
        assertEquals(ExportRoomBoundHandoffCoordinator.Preparation.Rejected(
            SnapshotRejection.INELIGIBLE_NOTE,
        ), service().prepareNew(noteId))
        assertEquals(0, db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM export_operations",
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun missingImageOriginalAfterCommitLeavesUnreleasablePins() = runBlocking {
        val (asset, original) = attachImage()
        assertTrue(original.delete()) // ONLY isolated test-owned original
        val result = service().prepareNew(noteId)
        assertTrue(result is ExportRoomBoundHandoffCoordinator.Preparation.IncompleteRetainedUntrusted)
        val op = (result as ExportRoomBoundHandoffCoordinator.Preparation.IncompleteRetainedUntrusted).operationId
        assertProtected(op, listOf(asset))
        assertFalse(original.exists())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun providerSuccessRemainsReadbackUntrustedAndCannotRepeat() = runBlocking {
        val (asset, original) = attachImage()
        val old = original.readBytes()
        val prepared = prepared(service().prepareNew(noteId))
        val provider = DisposableDestination()
        val first = service().handoffToFreshDestination(prepared.operationId, provider)
        assertEquals(ExportRoomBoundHandoffCoordinator.Handoff.ReadbackObservedUntrusted(
            prepared.operationId,
        ), first)
        assertEquals(1, provider.openCount)
        assertEquals(1, provider.readCount)
        assertEquals(prepared.archiveSha256, digest(provider.bytes()))
        reopen()
        assertEquals(ExportRoomBoundHandoffCoordinator.Recovery.ReadbackObservedUntrusted(
            prepared.operationId,
        ), service().inspectReadOnly(prepared.operationId))
        val replay = DisposableDestination("ef".repeat(32))
        assertEquals(ExportRoomBoundHandoffCoordinator.Handoff.AttemptOutcomeUnknownUntrusted(
            prepared.operationId,
        ), service().handoffToFreshDestination(prepared.operationId, replay))
        assertEquals(0, replay.openCount)
        assertProtected(prepared.operationId, listOf(asset))
        assertArrayEquals(old, original.readBytes())
    }

    @Test fun refusedProviderWritePersistsPendingAndCannotRetry() = runBlocking {
        val (asset, original) = attachImage()
        val prepared = prepared(service().prepareNew(noteId))
        val provider = DisposableDestination(failOpen = true)
        assertEquals(ExportRoomBoundHandoffCoordinator.Handoff.AttemptOutcomeUnknownUntrusted(
            prepared.operationId,
        ), service().handoffToFreshDestination(prepared.operationId, provider))
        assertEquals(1, provider.openCount)
        reopen()
        assertEquals(ExportRoomBoundHandoffCoordinator.Recovery.AttemptPendingUntrusted(
            prepared.operationId,
        ), service().inspectReadOnly(prepared.operationId))
        val replay = DisposableDestination("ae".repeat(32))
        assertEquals(ExportRoomBoundHandoffCoordinator.Handoff.AttemptOutcomeUnknownUntrusted(
            prepared.operationId,
        ), service().handoffToFreshDestination(prepared.operationId, replay))
        assertEquals(0, replay.openCount)
        assertProtected(prepared.operationId, listOf(asset))
        assertTrue(original.isFile)
    }

    @Test fun refusedReadbackAfterProviderWriteRemainsPending() = runBlocking {
        val prepared = prepared(service().prepareNew(noteId))
        val provider = DisposableDestination(failReadback = true)
        assertEquals(ExportRoomBoundHandoffCoordinator.Handoff.AttemptOutcomeUnknownUntrusted(
            prepared.operationId,
        ), service().handoffToFreshDestination(prepared.operationId, provider))
        assertTrue(provider.bytes().isNotEmpty())
        reopen()
        assertEquals(ExportRoomBoundHandoffCoordinator.Recovery.AttemptPendingUntrusted(
            prepared.operationId,
        ), service().inspectReadOnly(prepared.operationId))
        assertProtected(prepared.operationId)
    }

    @Test fun corruptPrivateArchiveBeforeAttemptIsNotAdoptedOnRestart() = runBlocking {
        val prepared = prepared(service().prepareNew(noteId))
        val archive = File(root, "retained-private-export-archives/${prepared.operationId}.notebook")
        val bytes = archive.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        archive.writeBytes(bytes) // ONLY disposable test-private archive
        reopen()
        assertEquals(ExportRoomBoundHandoffCoordinator.Recovery.IncompleteRetainedUntrusted(
            prepared.operationId,
        ), service().inspectReadOnly(prepared.operationId))
        assertProtected(prepared.operationId)
    }

    @Test fun unknownOrMalformedOperationNeverStartsProvider() = runBlocking {
        val invalid = "not-an-operation"
        assertEquals(ExportRoomBoundHandoffCoordinator.Recovery.IncompleteRetainedUntrusted(
            invalid,
        ), service().inspectReadOnly(invalid))
        val destination = DisposableDestination()
        assertEquals(ExportRoomBoundHandoffCoordinator.Handoff.AttemptOutcomeUnknownUntrusted(
            invalid,
        ), service().handoffToFreshDestination(invalid, destination))
        assertEquals(0, destination.openCount)
    }

    @Test fun twoExplicitNewPreparesHaveDistinctImmutableOperations() = runBlocking {
        val one = prepared(service().prepareNew(noteId))
        val two = prepared(service().prepareNew(noteId))
        assertNotEquals(one.operationId, two.operationId)
        assertProtected(one.operationId)
        assertProtected(two.operationId)
        assertEquals(ExportRoomBoundHandoffCoordinator.Recovery.NoAttemptRecordedUntrusted(
            one.operationId,
        ), service().inspectReadOnly(one.operationId))
        assertEquals(ExportRoomBoundHandoffCoordinator.Recovery.NoAttemptRecordedUntrusted(
            two.operationId,
        ), service().inspectReadOnly(two.operationId))
    }
}
