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

/** Isolated JVM filesystem proof; real Android directory fsync still needs API 26/37 evidence. */
class ExportRetainedPrivateStageStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun id(): String = UUID.randomUUID().toString()
    private fun limits() = ExportPrivateStageLimits(
        maxAssets = 4, maxAssetBytes = 1_024, maxTotalAssetBytes = 4_096,
        maxMetadataBytes = 4_096, maxExistingStages = 6,
    )
    private fun root(): File = temp.newFolder()
    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    private fun store(
        root: File, sync: (File) -> Unit = {},
        afterChunk: (String, Long) -> Unit = { _, _ -> },
    ) = ExportRetainedPrivateStageStore(root, limits(), sync, afterChunk = afterChunk)

    @Test fun exactCopiesSnapshotAndManifestRemainPrivateAndUntrusted() = runBlocking {
        val root = root()
        val operation = id()
        val sourceId = id()
        val noteId = id()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val staging = store(root).createNew(operation)
        val snapshotHash = staging.writeCapturedSnapshot("captured document".toByteArray())
        val copy = staging.copyAsset(
            ExpectedEncodedImage(sourceId, "image/png", sha(bytes), bytes.size.toLong()), bytes,
        )
        val manifestHash = staging.writeCopyManifest("untrusted manifest".toByteArray())
        val observed = staging.observeUntrusted(noteId, 3L, listOf(sourceId))
        assertEquals(operation, observed.operationId)
        assertEquals(noteId, observed.noteId)
        assertEquals(snapshotHash, observed.snapshotSha256)
        assertEquals(manifestHash, observed.manifestSha256)
        assertEquals(listOf(copy), observed.copies)
        val stageDir = File(root, "pending-export-copies/$operation")
        assertEquals(
            setOf("snapshot.json", "manifest.json", "asset-$sourceId.bin"),
            stageDir.listFiles().orEmpty().map { it.name }.toSet(),
        )
        assertArrayEquals(bytes, File(stageDir, "asset-$sourceId.bin").readBytes())
        assertFalse(File(root, "assets").exists())
        assertFalse(File(stageDir, "receipt").exists())
        assertFalse(File(stageDir, "seal").exists())
    }

    @Test fun wrongSourceDigestLeavesTaintedStageAndCannotReportCompletion() = runBlocking {
        val root = root()
        val operation = id()
        val staging = store(root).createNew(operation)
        staging.writeCapturedSnapshot(byteArrayOf(1))
        val bytes = byteArrayOf(9, 8)
        val failure = runCatching {
            staging.copyAsset(ExpectedEncodedImage(id(), "image/png", "0".repeat(64), 2), bytes)
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(runCatching {
            staging.writeCopyManifest(byteArrayOf(2))
        }.exceptionOrNull() is IOException)
        assertTrue(runCatching {
            staging.observeUntrusted(id(), 0L, emptyList())
        }.exceptionOrNull() is IOException)
        assertTrue(File(root, "pending-export-copies/$operation").isDirectory)
    }

    @Test fun copyWriteFailureLeavesPartialStageAndPreventsRetry() = runBlocking {
        val root = root()
        val op = id()
        val source = id()
        val bytes = ByteArray(9) { it.toByte() }
        val staging = store(root, afterChunk = { name, count ->
            if (name.startsWith("asset-") && count > 0L) throw IOException("Injected crash")
        }).createNew(op)
        staging.writeCapturedSnapshot(byteArrayOf(1))
        assertTrue(runCatching {
            staging.copyAsset(ExpectedEncodedImage(source, "image/png", sha(bytes), 9), bytes)
        }.exceptionOrNull() is IOException)
        assertTrue(File(root, "pending-export-copies/$op/asset-$source.bin").exists())
        assertTrue(runCatching {
            staging.copyAsset(ExpectedEncodedImage(source, "image/png", sha(bytes), 9), bytes)
        }.exceptionOrNull() is IOException)
        assertFalse(File(root, "pending-export-copies/$op/manifest.json").exists())
        assertFalse(File(root, "assets").exists())
    }

    @Test fun sourceCollisionDoesNotAdoptOrOverwriteExistingStage() = runBlocking {
        val root = root()
        val op = id()
        val first = store(root).createNew(op)
        first.writeCapturedSnapshot(byteArrayOf(1, 2))
        assertTrue(runCatching { store(root).createNew(op) }.exceptionOrNull() is IOException)
        assertArrayEquals(byteArrayOf(1, 2),
            File(root, "pending-export-copies/$op/snapshot.json").readBytes())
    }

    @Test fun snapshotDirectorySyncFailureLeavesNoFalseSuccess() = runBlocking {
        val root = root()
        val op = id()
        val staging = store(root, sync = { target ->
            if (target.name == op) throw IOException("Injected fsync failure")
        }).createNew(op)
        assertTrue(runCatching {
            staging.writeCapturedSnapshot(byteArrayOf(1, 2, 3))
        }.exceptionOrNull() is IOException)
        assertTrue(File(root, "pending-export-copies/$op/snapshot.json").exists())
        assertTrue(runCatching {
            staging.writeCopyManifest(byteArrayOf(4))
        }.exceptionOrNull() is IOException)
    }
}
