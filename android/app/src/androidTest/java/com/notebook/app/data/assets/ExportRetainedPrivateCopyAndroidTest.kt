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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room, files, Android fsync and shared OS lease; isolated disposable test data only. */
@RunWith(AndroidJUnit4::class)
class ExportRetainedPrivateCopyAndroidTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var name: String
    private lateinit var root: File
    private lateinit var noteId: String
    private lateinit var pageId: String

    private fun id(): String = UUID.randomUUID().toString()
    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    private val snapLimits = SnapshotLimits(4, 16, 4, 100_000, 400_000, 100_000, 4, 16)
    private val copyLimits = ExportCopyAdmissionLimits(4, 100_000, 400_000)
    private val stageLimits = ExportPrivateStageLimits(4, 100_000, 400_000, 100_000, 8)

    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "sto08-d2b-" + id() + ".sqlite"
        check(name != AppDatabaseProvider.DATABASE_NAME)
        root = File(context.filesDir, "sto08-d2b-private-" + id())
        assertTrue(root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, name)
        val book = id()
        noteId = id()
        pageId = id()
        db.notebookDao().insert(NotebookEntity(book, null, "Test", sortOrder = 0L,
            createdAtMillis = 1L, updatedAtMillis = 1L))
        db.noteDao().insert(NoteEntity(noteId, book, "Retention test", 1L, 1L, revision = 2L))
        db.pageDao().insert(PageEntity(pageId, noteId, 0L, 600.0, 800.0,
            """{"type":"blank","color":"#FFFFFF"}""", 1L))
    }

    @After fun teardown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::name.isInitialized) context.deleteDatabase(name)
        if (::root.isInitialized) assertTrue("Could not remove isolated fixture", root.deleteRecursively())
    }

    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(19, 105, 171))
            ByteArrayOutputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 95, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private suspend fun registerImage(bytes: ByteArray): Pair<String, File> {
        val digest = sha(bytes)
        val assetId = id()
        val relative = "assets/" + digest.take(2) + "/" + digest.substring(2, 4) + "/" +
            digest + ".png"
        val source = File(root, relative)
        assertTrue(source.parentFile!!.mkdirs())
        source.writeBytes(bytes)
        db.assetDao().insert(AssetEntity(assetId, digest, "image/png",
            bytes.size.toLong(), relative, "finalized"))
        val element = id()
        val json = """{"id":"$element","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""
        db.elementDao().insert(ElementEntity(element, pageId, 0, "image", json, 1L))
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(element, assetId))
        return assetId to source
    }

    private fun coordinator(): ExportRetainedPrivateCopyCoordinator =
        ExportHoldAdmissionComposition.retainedPrivateCopyForRoom(root, db, stageLimits)

    @Test fun capturesHoldsCopiesAndManifestWithoutTouchingOriginal() = runBlocking {
        val bytes = imageBytes()
        val (asset, original) = registerImage(bytes)
        val operation = id()
        val outcome = coordinator().stage(operation, SnapshotRequest(noteId, snapLimits),
            copyLimits, 123L)
        assertTrue(outcome is ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted)
        val observed = (outcome as ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted).observation
        assertEquals(noteId, observed.noteId)
        assertEquals(2L, observed.noteRevision)
        assertEquals(listOf(asset), observed.copies.map { it.assetId })
        assertEquals(listOf(asset), db.exportHoldAdmissionDao().listHolds(operation).map { it.assetId })
        val stageDir = File(root, "pending-export-copies/$operation")
        assertArrayEquals(bytes, File(stageDir, "asset-$asset.bin").readBytes())
        assertTrue(File(stageDir, "snapshot.json").readText().contains(noteId))
        assertTrue(File(stageDir, "manifest.json").readText().contains(asset))
        assertFalse(File(stageDir, "receipt").exists())
        assertArrayEquals(bytes, original.readBytes())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun missingOriginalAfterHoldCommitFailsButRetainsPins() = runBlocking {
        val bytes = imageBytes()
        val (asset, original) = registerImage(bytes)
        assertTrue(original.delete()) // ONLY isolated test-original, never application library
        val operation = id()
        val failure = runCatching {
            coordinator().stage(operation, SnapshotRequest(noteId, snapLimits),
                copyLimits, 123L)
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(listOf(asset), db.exportHoldAdmissionDao().listHolds(operation).map { it.assetId })
        assertTrue(File(root, "pending-export-copies/$operation").exists())
        assertFalse(File(root, "pending-export-copies/$operation/manifest.json").exists())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun stagedSnapshotCanBeRecheckedAfterRoomReopenAndDraftedWithoutRelease() = runBlocking {
        val bytes = imageBytes()
        val (asset, original) = registerImage(bytes)
        val operation = id()
        val outcome = coordinator().stage(operation, SnapshotRequest(noteId, snapLimits),
            copyLimits, 123L)
        assertTrue(outcome is ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted)
        val observation = (outcome as ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted).observation
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        val inspection = ExportPrivateStageReadOnlyVerifier(root, stageLimits).inspect(observation)
        assertEquals(ExportPrivateStageReadOnlyVerifier.Assessment.MatchedUntrusted(observation), inspection)
        val draft = ExportPrivatePortableDraftWriter(
            root, stageLimits, ExportAndroidDirectorySync::syncOrThrow,
        ).writeDraft(observation)
        assertEquals(noteId, draft.noteId)
        ZipFile(File(root, "pending-export-copies/$operation/draft.notebook")).use { zip ->
            assertNotNull(zip.getEntry("manifest.json"))
            val assetName = "assets/" + observation.copies.single().sha256 + ".png"
            assertArrayEquals(bytes,
                zip.getInputStream(zip.getEntry(assetName)).use { it.readBytes() })
        }
        assertEquals(listOf(asset), db.exportHoldAdmissionDao().listHolds(operation).map { it.assetId })
        assertArrayEquals(bytes, original.readBytes())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun tamperedPrivateCopyAfterRoomReopenCannotBecomePortableDraft() = runBlocking {
        val bytes = imageBytes()
        val (asset, original) = registerImage(bytes)
        val operation = id()
        val outcome = coordinator().stage(operation, SnapshotRequest(noteId, snapLimits),
            copyLimits, 123L)
        assertTrue(outcome is ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted)
        val observation = (outcome as ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted).observation
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        val copy = File(root, "pending-export-copies/$operation/" +
            observation.copies.single().fileName)
        copy.writeBytes(bytes.reversedArray()) // Only isolated stage fixture is changed.
        val assessment = ExportPrivateStageReadOnlyVerifier(root, stageLimits).inspect(observation)
        assertEquals(ExportPrivateStageReadOnlyVerifier.Assessment.Rejected(
            ExportPrivateStageReadOnlyVerifier.Reason.BYTES_MISMATCH), assessment)
        assertTrue(runCatching {
            ExportPrivatePortableDraftWriter(
                root, stageLimits, ExportAndroidDirectorySync::syncOrThrow,
            ).writeDraft(observation)
        }.exceptionOrNull() is IOException)
        assertFalse(File(root, "pending-export-copies/$operation/draft.notebook").exists())
        assertEquals(listOf(asset), db.exportHoldAdmissionDao().listHolds(operation).map { it.assetId })
        assertArrayEquals(bytes, original.readBytes())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun recoveryDerivesExactPinnedCopyExpectationsFromRoomV6WithoutCallerClaim() = runBlocking {
        val bytes = imageBytes()
        val (asset, original) = registerImage(bytes)
        val operation = id()
        val result = coordinator().stage(operation, SnapshotRequest(noteId, snapLimits),
            copyLimits, 123L)
        assertTrue(result is ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)

        val recovery = ExportBoundPrivateStageRecovery(db, root, stageLimits)
        val first = recovery.inspectReadOnly(operation)
        assertTrue(first is ExportBoundPrivateStageRecovery.Result.BoundStageUntrusted)
        val expectation = (first as ExportBoundPrivateStageRecovery.Result.BoundStageUntrusted).observation
        assertEquals(listOf(asset), expectation.copies.map { it.assetId })
        assertNull(first.draft)
        ExportPrivatePortableDraftWriter(
            root, stageLimits, ExportAndroidDirectorySync::syncOrThrow,
        ).writeDraft(expectation)
        val second = recovery.inspectReadOnly(operation)
        assertTrue(second is ExportBoundPrivateStageRecovery.Result.BoundStageUntrusted)
        assertNotNull((second as ExportBoundPrivateStageRecovery.Result.BoundStageUntrusted).draft)
        assertEquals(listOf(asset), db.exportHoldAdmissionDao().listHolds(operation).map { it.assetId })
        assertArrayEquals(bytes, original.readBytes())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun immutableRoomBindingRejectsTamperedCopyAndRetainsOriginal() = runBlocking {
        val bytes = imageBytes()
        val (asset, original) = registerImage(bytes)
        val operation = id()
        val result = coordinator().stage(operation, SnapshotRequest(noteId, snapLimits),
            copyLimits, 123L)
        assertTrue(result is ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        val stageFile = File(root, "pending-export-copies/" + operation +
            "/asset-" + asset + ".bin")
        stageFile.writeBytes(bytes.reversedArray()) // disposable export-owned test fixture only
        val recovery = ExportBoundPrivateStageRecovery(db, root, stageLimits)
        assertEquals(
            ExportBoundPrivateStageRecovery.Result.Incomplete(
                ExportBoundPrivateStageRecovery.Stop.STAGE_INTEGRITY_FAILED),
            recovery.inspectReadOnly(operation),
        )
        assertNotNull(db.exportHoldAdmissionDao().findSourceBinding(operation))
        assertEquals(listOf(asset), db.exportHoldAdmissionDao().listHolds(operation).map { it.assetId })
        assertArrayEquals(bytes, original.readBytes())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun unknownAndLegacyUnboundExportOperationsAreNeverAdopted() = runBlocking {
        assertEquals(
            ExportBoundPrivateStageRecovery.Result.Incomplete(
                ExportBoundPrivateStageRecovery.Stop.LEGACY_UNBOUND),
            ExportBoundPrivateStageRecovery(db, root, stageLimits).inspectReadOnly(id()),
        )
        val legacy = id()
        db.exportHoldAdmissionDao().insertOperation(
            com.notebook.app.data.local.entity.ExportOperationEntity(
                operationId = legacy, noteId = noteId, noteRevision = 2L,
                state = "COMMITTED_PIN_SET", createdAtMillis = 1L, updatedAtMillis = 1L,
                protocolVersion = 1,
            ),
        )
        assertEquals(
            ExportBoundPrivateStageRecovery.Result.Incomplete(
                ExportBoundPrivateStageRecovery.Stop.LEGACY_UNBOUND),
            ExportBoundPrivateStageRecovery(db, root, stageLimits).inspectReadOnly(legacy),
        )
        assertNull(db.exportHoldAdmissionDao().findSourceBinding(legacy))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun v6BoundArchiveCopySurvivesRoomReopenWithoutReleasingHolds() = runBlocking {
        val bytes = imageBytes()
        val (asset, original) = registerImage(bytes)
        val operation = id()
        assertTrue(coordinator().stage(
            operation, SnapshotRequest(noteId, snapLimits), copyLimits, 123L,
        ) is ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted)

        val recovery = ExportBoundPrivateStageRecovery(db, root, stageLimits)
        val stageResult = recovery.inspectReadOnly(operation)
        assertTrue(stageResult is ExportBoundPrivateStageRecovery.Result.BoundStageUntrusted)
        val observation = (stageResult as
            ExportBoundPrivateStageRecovery.Result.BoundStageUntrusted).observation
        val draft = ExportPrivatePortableDraftWriter(
            root, stageLimits, ExportAndroidDirectorySync::syncOrThrow,
        ).writeDraft(observation)

        val service = ExportHoldAdmissionComposition.independentPrivateArchiveForRoom(
            root, db, stageLimits,
        )
        val first = service.retainPrivateCopy(operation)
        assertTrue(first is ExportIndependentPrivateArchiveCoordinator.Result.PrivateCopyObservedUntrusted)
        val privateCopy = (first as
            ExportIndependentPrivateArchiveCoordinator.Result.PrivateCopyObservedUntrusted).archive
        assertEquals(draft.sha256, privateCopy.sha256)
        val separateFile = File(root,
            "retained-private-export-archives/" + operation + ".notebook")
        assertTrue(separateFile.isFile)
        assertArrayEquals(
            File(root, "pending-export-copies/" + operation + "/draft.notebook").readBytes(),
            separateFile.readBytes(),
        )
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        val reopened = ExportHoldAdmissionComposition.independentPrivateArchiveForRoom(
            root, db, stageLimits,
        )
        assertEquals(first, reopened.inspectPrivateCopyReadOnly(operation))
        assertTrue(runCatching {
            reopened.retainPrivateCopy(operation)
        }.exceptionOrNull() is IOException)
        assertEquals(listOf(asset),
            db.exportHoldAdmissionDao().listHolds(operation).map { it.assetId })
        assertNotNull(db.exportHoldAdmissionDao().findSourceBinding(operation))
        assertArrayEquals(bytes, original.readBytes())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun damagedRetainedCopyAfterRoomReopenNeverPassesInspection() = runBlocking {
        val bytes = imageBytes()
        val (asset, original) = registerImage(bytes)
        val operation = id()
        assertTrue(coordinator().stage(
            operation, SnapshotRequest(noteId, snapLimits), copyLimits, 123L,
        ) is ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted)
        val bound = ExportBoundPrivateStageRecovery(
            db, root, stageLimits,
        ).inspectReadOnly(operation) as ExportBoundPrivateStageRecovery.Result.BoundStageUntrusted
        ExportPrivatePortableDraftWriter(
            root, stageLimits, ExportAndroidDirectorySync::syncOrThrow,
        ).writeDraft(bound.observation)
        val service = ExportHoldAdmissionComposition.independentPrivateArchiveForRoom(
            root, db, stageLimits,
        )
        assertTrue(service.retainPrivateCopy(operation) is
            ExportIndependentPrivateArchiveCoordinator.Result.PrivateCopyObservedUntrusted)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        val archive = File(root,
            "retained-private-export-archives/" + operation + ".notebook")
        archive.writeBytes(archive.readBytes().reversedArray())
        assertTrue(ExportHoldAdmissionComposition.independentPrivateArchiveForRoom(
            root, db, stageLimits,
        ).inspectPrivateCopyReadOnly(operation) is
            ExportIndependentPrivateArchiveCoordinator.Result.Incomplete)
        assertEquals(listOf(asset),
            db.exportHoldAdmissionDao().listHolds(operation).map { it.assetId })
        assertArrayEquals(bytes, original.readBytes())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    private suspend fun retainReadyForUserDocument(operationId: String) {
        assertTrue(coordinator().stage(
            operationId, SnapshotRequest(noteId, snapLimits), copyLimits, 123L,
        ) is ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted)
        val bound = ExportBoundPrivateStageRecovery(
            db, root, stageLimits,
        ).inspectReadOnly(operationId) as
            ExportBoundPrivateStageRecovery.Result.BoundStageUntrusted
        ExportPrivatePortableDraftWriter(
            root, stageLimits, ExportAndroidDirectorySync::syncOrThrow,
        ).writeDraft(bound.observation)
        val retained = ExportHoldAdmissionComposition.independentPrivateArchiveForRoom(
            root, db, stageLimits,
        ).retainPrivateCopy(operationId)
        assertTrue(retained is ExportIndependentPrivateArchiveCoordinator.Result.PrivateCopyObservedUntrusted)
    }

    @Test fun completeRoomBoundExternalByteRereadNeverReleasesOriginalOrHold() = runBlocking {
        val originalBytes = imageBytes()
        val (asset, original) = registerImage(originalBytes)
        val operationId = id()
        retainReadyForUserDocument(operationId)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        val external = ByteArrayOutputStream()
        val dest = object : ExportExternalDocumentDestination {
            override val destinationIdentitySha256: String = "1".repeat(64)
            override fun openWriteOnce(): ExportExternalDocumentDestination.Sink =
                object : ExportExternalDocumentDestination.Sink {
                    override val output = external
                    override fun syncOrThrow() {}
                    override fun close() { output.flush() }
                }
            override fun openReadBack(): java.io.InputStream =
                java.io.ByteArrayInputStream(external.toByteArray())
        }
        val result = ExportHoldAdmissionComposition.safPublicationForRoom(
            root, db, stageLimits,
        ).publishToFreshUserDocument(operationId, dest)
        assertTrue(result is ExportSafPublicationCoordinator.Result.ExternalBytesObservedUntrusted)
        val archive = File(root, "retained-private-export-archives/" + operationId + ".notebook")
        assertArrayEquals(archive.readBytes(), external.toByteArray())
        assertEquals(listOf(asset),
            db.exportHoldAdmissionDao().listHolds(operationId).map { it.assetId })
        assertNotNull(db.exportHoldAdmissionDao().findSourceBinding(operationId))
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun externalReadbackCorruptionDoesNotChangeOriginalOrCommittedPins() = runBlocking {
        val originalBytes = imageBytes()
        val (asset, original) = registerImage(originalBytes)
        val operationId = id()
        retainReadyForUserDocument(operationId)
        val external = ByteArrayOutputStream()
        val corrupt = object : ExportExternalDocumentDestination {
            override val destinationIdentitySha256: String = "1".repeat(64)
            override fun openWriteOnce(): ExportExternalDocumentDestination.Sink =
                object : ExportExternalDocumentDestination.Sink {
                    override val output = external
                    override fun syncOrThrow() {}
                    override fun close() { output.flush() }
                }
            override fun openReadBack(): java.io.InputStream =
                java.io.ByteArrayInputStream(external.toByteArray().reversedArray())
        }
        val failure = runCatching {
            ExportHoldAdmissionComposition.safPublicationForRoom(
                root, db, stageLimits,
            ).publishToFreshUserDocument(operationId, corrupt)
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(listOf(asset),
            db.exportHoldAdmissionDao().listHolds(operationId).map { it.assetId })
        assertNotNull(db.exportHoldAdmissionDao().findSourceBinding(operationId))
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun trashedNoteRejectsBeforeCreatingPinsOrStage() = runBlocking {
        val bytes = imageBytes()
        val (_, original) = registerImage(bytes)
        val note = db.noteDao().findById(noteId)!!
        db.noteDao().update(note.copy(deletedAtMillis = 7L))
        val operation = id()
        assertEquals(
            ExportRetainedPrivateCopyCoordinator.Result.Rejected(SnapshotRejection.INELIGIBLE_NOTE),
            coordinator().stage(operation, SnapshotRequest(noteId, snapLimits), copyLimits, 123L),
        )
        assertNull(db.exportHoldAdmissionDao().findOperation(operation))
        assertFalse(File(root, "pending-export-copies/$operation").exists())
        assertArrayEquals(bytes, original.readBytes())
    }
}
