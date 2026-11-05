package com.notebook.app.data.assets

import com.notebook.app.data.local.dao.AssetDao
import com.notebook.app.data.local.entity.AssetEntity
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadOnlyFinalizedAssetVerifierTest {
    @get:Rule val temp = TemporaryFolder()

    // Deliberately signature-only, NOT a decodable PNG. This test checks integrity, not decoding.
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x00,
    )

    private fun finalized() = ManagedAssetFileStore(temp.root, directorySync = {})
        .stageAndFinalize(ByteArrayInputStream(png), "image/png")

    private fun row(file: ManagedAssetFileStore.FinalizedFile) = AssetEntity(
        "asset-id", file.sha256, file.mimeType, file.byteSize, file.relativePath, "finalized"
    )

    private fun verifier(dao: FakeDao, max: Long = 64L * 1024L * 1024L) =
        ReadOnlyFinalizedAssetVerifier(temp.root, dao, maximumBytes = max)

    private suspend fun expectRejection(dao: FakeDao, max: Long = 64L * 1024L * 1024L) {
        val failure = runCatching { verifier(dao, max).verify("asset-id") }.exceptionOrNull()
        assertTrue("Expected fail-closed IOException, got $failure", failure is IOException)
        assertEquals(0, dao.writes)
    }

    @Test fun matchingFinalizedAssetProducesIntegrityObservationWithoutMutation() = runBlocking {
        val file = finalized()
        val expected = row(file)
        val dao = FakeDao(mutableListOf(expected))
        val original = File(temp.root, file.relativePath).readBytes()

        assertEquals(
            ReadOnlyFinalizedAssetVerifier.IntegritySnapshot(
                "asset-id", file.sha256, "image/png", png.size.toLong()
            ),
            verifier(dao).verify("asset-id")
        )
        assertEquals(expected, dao.rows.single())
        assertArrayEquals(original, File(temp.root, file.relativePath).readBytes())
        assertEquals(0, dao.writes)
    }

    @Test fun absentAssetIdIsRejected() = runBlocking {
        expectRejection(FakeDao())
    }

    @Test fun blankAssetIdIsRejectedWithoutDaoWrite() = runBlocking {
        val dao = FakeDao()
        val failure = runCatching { verifier(dao).verify(" ") }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(0, dao.writes)
    }

    @Test fun nonFinalizedMetadataIsRejected() = runBlocking {
        val file = finalized()
        expectRejection(FakeDao(mutableListOf(row(file).copy(state = "quarantined"))))
    }

    @Test fun traversalPathIsRejectedWithoutOpeningOutsideRoot() = runBlocking {
        val file = finalized()
        val dao = FakeDao(mutableListOf(row(file).copy(relativePath = "../outside.png")))
        expectRejection(dao)
        assertArrayEquals(png, File(temp.root, file.relativePath).readBytes())
    }

    @Test fun incorrectMimeAndMalformedDigestAreRejected() = runBlocking {
        val file = finalized()
        val original = row(file)
        expectRejection(FakeDao(mutableListOf(original.copy(mimeType = "text/plain"))))
        expectRejection(FakeDao(mutableListOf(original.copy(sha256 = "INVALID"))))
        expectRejection(FakeDao(mutableListOf(original.copy(mimeType = "application/pdf"))))
    }

    @Test fun missingOriginalIsRejectedWithoutChangingMetadata() = runBlocking {
        val file = finalized()
        val dao = FakeDao(mutableListOf(row(file)))
        assertTrue(File(temp.root, file.relativePath).delete())
        expectRejection(dao)
        assertEquals("finalized", dao.rows.single().state)
    }

    @Test fun mismatchedMetadataSizeIsRejected() = runBlocking {
        val file = finalized()
        expectRejection(FakeDao(mutableListOf(row(file).copy(byteSize = file.byteSize + 1))))
    }

    @Test fun corruptBytesOfSameLengthAreRejectedWithoutRepair() = runBlocking {
        val file = finalized()
        val target = File(temp.root, file.relativePath)
        val corrupted = ByteArray(png.size) { 0x55.toByte() }
        target.writeBytes(corrupted)
        expectRejection(FakeDao(mutableListOf(row(file))))
        assertArrayEquals(corrupted, target.readBytes())
    }

    @Test fun matchingDigestWithBadSignatureIsRejected() = runBlocking {
        val bad = ByteArray(12) { 0x21.toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bad)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val path = "assets/${digest.substring(0, 2)}/${digest.substring(2, 4)}/$digest.png"
        val target = File(temp.root, path)
        assertTrue(target.parentFile!!.mkdirs())
        target.writeBytes(bad)
        val dao = FakeDao(mutableListOf(AssetEntity(
            "asset-id", digest, "image/png", bad.size.toLong(), path, "finalized"
        )))
        expectRejection(dao)
        assertArrayEquals(bad, target.readBytes())
    }

    @Test fun configuredByteLimitIsEnforced() = runBlocking {
        val file = finalized()
        expectRejection(FakeDao(mutableListOf(row(file))), max = png.size.toLong() - 1)
    }

    private class FakeDao(val rows: MutableList<AssetEntity> = mutableListOf()) : AssetDao {
        var writes = 0
        override suspend fun insert(asset: AssetEntity) { writes++; rows.add(asset) }
        override suspend fun findById(id: String): AssetEntity? = rows.firstOrNull { it.id == id }
        override suspend fun findBySha256(sha256: String): AssetEntity? = rows.firstOrNull { it.sha256 == sha256 }
        override suspend fun findByRelativePath(relativePath: String): AssetEntity? =
            rows.firstOrNull { it.relativePath == relativePath }
        override suspend fun listByState(state: String, limit: Int): List<AssetEntity> =
            rows.filter { it.state == state }.take(limit)
        override suspend fun listAfterId(afterId: String?, limit: Int): List<AssetEntity> =
            rows.filter { afterId == null || it.id > afterId }.sortedBy { it.id }.take(limit)
        override suspend fun countAll(): Int = rows.size
    }
}
