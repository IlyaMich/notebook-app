package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Read-only post-process-crash diagnostic. Fixture does not use application assets. */
class ExportPrivateStageReadOnlyVerifierTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun id() = UUID.randomUUID().toString()
    private val limits = ExportPrivateStageLimits(4, 4096, 8192, 4096, 8)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class Fixture(
        val root: File,
        val observation: ExportUntrustedPrivateStageObservation,
        val sourceBytes: ByteArray,
    ) {
        fun stageDir() = File(root, "pending-export-copies/" + observation.operationId)
    }

    private suspend fun fixture(
        forgedNoteId: Boolean = false,
    ): Fixture {
        val root = tmp.newFolder()
        val op = id()
        val note = id()
        val source = id()
        val bytes = byteArrayOf(10, 20, 30, 40, 50, 60)
        val digest = sha(bytes)
        val store = ExportRetainedPrivateStageStore(root, limits, syncDirectory = {})
        val stage = store.createNew(op)
        val observedNote = if (forgedNoteId) id() else note
        val snapshot = """{"format":"internal-untrusted-captured-note-v1",
            "operationId":"@OP@","note":{"id":"@NOTE@","revision":3},
            "pages":[{"id":"@PAGE@","noteId":"@NOTE@"}],
            "assets":[{"id":"@ASSET@","sha256":"@SHA@","encodedBytes":6,"state":"finalized"}],
            "imageOwners":[{"noteId":"@NOTE@","assetId":"@ASSET@"}]}"""
            .replace("@OP@", op).replace("@NOTE@", observedNote)
            .replace("@PAGE@", id()).replace("@ASSET@", source).replace("@SHA@", digest)
        val snapshotDigest = stage.writeCapturedSnapshot(snapshot.toByteArray())
        stage.copyAsset(ExpectedEncodedImage(source, "image/png", digest, bytes.size.toLong()), bytes)
        val manifest = """{"format":"internal-untrusted-copy-manifest-v1",
            "operationId":"@OP@","noteId":"@NOTE@","noteRevision":3,
            "snapshotSha256":"@SNAP@","copies":[{"assetId":"@ASSET@",
            "fileName":"asset-@ASSET@.bin","byteCount":6,"sha256":"@SHA@"}]}"""
            .replace("@OP@", op).replace("@NOTE@", note)
            .replace("@SNAP@", snapshotDigest).replace("@ASSET@", source)
            .replace("@SHA@", digest)
        stage.writeCopyManifest(manifest.toByteArray())
        return Fixture(root, stage.observeUntrusted(note, 3L, listOf(source)), bytes)
    }

    @Test fun intactStageMatchesOnlyAsUntrustedObservation() = runBlocking {
        val f = fixture()
        val before = f.stageDir().listFiles().orEmpty().map { it.name }.sorted()
        val actual = ExportPrivateStageReadOnlyVerifier(f.root, limits).inspect(f.observation)
        assertEquals(ExportPrivateStageReadOnlyVerifier.Assessment.MatchedUntrusted(f.observation), actual)
        assertEquals(before, f.stageDir().listFiles().orEmpty().map { it.name }.sorted())
        assertFalse(File(f.root, "assets").exists())
        assertFalse(File(f.stageDir(), "receipt").exists())
    }

    @Test fun corruptedCopyWithSameLengthCannotMatch() = runBlocking {
        val f = fixture()
        val copy = f.observation.copies.single()
        File(f.stageDir(), copy.fileName).writeBytes(f.sourceBytes.reversedArray())
        assertEquals(ExportPrivateStageReadOnlyVerifier.Assessment.Rejected(
            ExportPrivateStageReadOnlyVerifier.Reason.BYTES_MISMATCH),
            ExportPrivateStageReadOnlyVerifier(f.root, limits).inspect(f.observation))
    }

    @Test fun missingManifestRejectedWithoutRepair() = runBlocking {
        val f = fixture()
        assertTrue(File(f.stageDir(), "manifest.json").delete())
        assertEquals(ExportPrivateStageReadOnlyVerifier.Assessment.Rejected(
            ExportPrivateStageReadOnlyVerifier.Reason.MISSING_FILE),
            ExportPrivateStageReadOnlyVerifier(f.root, limits).inspect(f.observation))
        assertFalse(File(f.stageDir(), "manifest.json").exists())
    }

    @Test fun unexpectedStageFileCannotBeSilentlyIgnored() = runBlocking {
        val f = fixture()
        File(f.stageDir(), "undeclared.bin").writeBytes(byteArrayOf(1))
        assertEquals(ExportPrivateStageReadOnlyVerifier.Assessment.Rejected(
            ExportPrivateStageReadOnlyVerifier.Reason.UNEXPECTED_ENTRY),
            ExportPrivateStageReadOnlyVerifier(f.root, limits).inspect(f.observation))
    }

    @Test fun contradictoryClaimedNoteFailsBeforeTrust() = runBlocking {
        val f = fixture(forgedNoteId = true)
        assertEquals(ExportPrivateStageReadOnlyVerifier.Assessment.Rejected(
            ExportPrivateStageReadOnlyVerifier.Reason.INVALID_SNAPSHOT),
            ExportPrivateStageReadOnlyVerifier(f.root, limits).inspect(f.observation))
    }

    @Test fun forgedExpectationCannotMatchStagePath() = runBlocking {
        val f = fixture()
        assertEquals(ExportPrivateStageReadOnlyVerifier.Assessment.Rejected(
            ExportPrivateStageReadOnlyVerifier.Reason.INVALID_EXPECTATION),
            ExportPrivateStageReadOnlyVerifier(f.root, limits).inspect(
                f.observation.copy(operationId = "not-an-export-uuid")))
    }

    @Test fun interruptedRereadCannotBeMistakenForCompletion() = runBlocking {
        val f = fixture()
        val verifier = ExportPrivateStageReadOnlyVerifier(
            f.root, limits, afterReadChunk = { _, _ -> throw IOException("injected read failure") })
        assertEquals(ExportPrivateStageReadOnlyVerifier.Assessment.Rejected(
            ExportPrivateStageReadOnlyVerifier.Reason.READ_FAILED),
            verifier.inspect(f.observation))
    }
}
