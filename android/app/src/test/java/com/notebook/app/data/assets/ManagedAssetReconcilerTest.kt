package com.notebook.app.data.assets

import com.notebook.app.data.local.dao.AssetDao
import com.notebook.app.data.local.entity.AssetEntity
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedAssetReconcilerTest {
    @get:Rule val temp = TemporaryFolder()
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x00,
    )

    private fun store() = ManagedAssetFileStore(temp.root, directorySync = {})
    private fun newFile() = store().stageAndFinalize(ByteArrayInputStream(png), "image/png")
    private fun row(file: ManagedAssetFileStore.FinalizedFile) = AssetEntity(
        "asset-id", file.sha256, file.mimeType, file.byteSize, file.relativePath, "finalized"
    )
    private fun scanner(dao: FakeDao, max: Int = 10_000, page: Int = 2) =
        ManagedAssetReconciler(temp.root, dao, maximumMetadataRows = max, pageSize = page)

    @Test fun verifiedRegisteredAssetIsReportedWithoutChanges() = runBlocking {
        val file = newFile()
        val dao = FakeDao(mutableListOf(row(file)))
        val before = File(temp.root, file.relativePath).readBytes()
        val result = scanner(dao).inspect()
        assertEquals(1, result.size)
        assertEquals(ManagedAssetReconciler.Kind.VERIFIED_REGISTERED, result.single().kind)
        assertEquals("asset-id", result.single().assetId)
        assertTrue(before.contentEquals(File(temp.root, file.relativePath).readBytes()))
        assertEquals(0, dao.writes)
    }

    @Test fun orphanedVerifiedFileIsReportedAndPreserved() = runBlocking {
        val file = newFile()
        val dao = FakeDao()
        val findings = scanner(dao).inspect()
        assertEquals(ManagedAssetReconciler.Kind.VALID_UNREGISTERED_FINAL, findings.single().kind)
        assertTrue(File(temp.root, file.relativePath).exists())
        assertEquals(0, dao.writes)
    }

    @Test fun missingRegisteredFileIsReportedButDatabaseRowIsPreserved() = runBlocking {
        val file = newFile()
        val dao = FakeDao(mutableListOf(row(file)))
        assertTrue(File(temp.root, file.relativePath).delete())
        val findings = scanner(dao).inspect()
        assertEquals(ManagedAssetReconciler.Kind.MISSING_OR_UNSCANNED_FINAL, findings.single().kind)
        assertEquals(1, dao.rows.size)
    }

    @Test fun corruptRegisteredFileIsReportedWithoutDeletingIt() = runBlocking {
        val file = newFile()
        val dao = FakeDao(mutableListOf(row(file)))
        File(temp.root, file.relativePath).writeBytes(byteArrayOf(1, 2, 3))
        val findings = scanner(dao).inspect()
        assertEquals(ManagedAssetReconciler.Kind.INVALID_OR_UNREADABLE_FINAL, findings.single().kind)
        assertEquals(ManagedAssetRecoveryInventory.Kind.HASH_MISMATCH, findings.single().filesystemKind)
        assertTrue(File(temp.root, file.relativePath).exists())
    }

    @Test fun mismatchedSizeAndStateAreNotReportedVerified() = runBlocking {
        val file = newFile()
        val wrong = row(file).copy(byteSize = file.byteSize + 1L, state = "quarantined")
        val result = scanner(FakeDao(mutableListOf(wrong))).inspect()
        assertEquals(ManagedAssetReconciler.Kind.METADATA_CONFLICT, result.single().kind)
    }

    @Test fun maliciousRelativePathIsNotAccessedOrTreatedAsVerified() = runBlocking {
        val file = newFile()
        val outside = row(file).copy(relativePath = "../elsewhere")
        val findings = scanner(FakeDao(mutableListOf(outside))).inspect()
        assertEquals(2, findings.size)
        assertTrue(findings.any { it.kind == ManagedAssetReconciler.Kind.METADATA_CONFLICT })
        assertTrue(findings.any { it.kind == ManagedAssetReconciler.Kind.VALID_UNREGISTERED_FINAL })
    }

    @Test fun stagingLeftoverIsNeverDeleted() = runBlocking {
        val staging = File(temp.root, "staging").apply { mkdir() }
        val orphan = File(staging, "asset-abcd.partial").apply { writeBytes(byteArrayOf(1)) }
        val findings = scanner(FakeDao()).inspect()
        assertEquals(ManagedAssetReconciler.Kind.STAGING_OR_UNEXPECTED_ENTRY, findings.single().kind)
        assertEquals(ManagedAssetRecoveryInventory.Kind.STAGING_FILE, findings.single().filesystemKind)
        assertTrue(orphan.exists())
    }

    @Test fun keysetPaginationIncludesAllRows() = runBlocking {
        val file = newFile()
        val base = row(file)
        val dao = FakeDao(mutableListOf(
            base.copy(id = "a", sha256 = "a".repeat(64), relativePath = "assets/aa/aa/${"a".repeat(64)}.png"),
            base.copy(id = "b", sha256 = "b".repeat(64), relativePath = "assets/bb/bb/${"b".repeat(64)}.png"),
            base.copy(id = "c", sha256 = "c".repeat(64), relativePath = "assets/cc/cc/${"c".repeat(64)}.png"),
        ))
        val findings = scanner(dao, page = 2).inspect()
        assertEquals(4, findings.size) // one unregistered final, three absent metadata paths
        assertEquals(3, findings.count { it.kind == ManagedAssetReconciler.Kind.MISSING_OR_UNSCANNED_FINAL })
    }

    @Test fun exceedingMetadataBoundFailsClosed() = runBlocking {
        val digest = "a".repeat(64)
        val dao = FakeDao(mutableListOf(
            AssetEntity("a", digest, "image/png", 12, "assets/aa/aa/$digest.png", "finalized"),
            AssetEntity("b", "b".repeat(64), "image/png", 12, "assets/bb/bb/${"b".repeat(64)}.png", "finalized"),
        ))
        val failure = runCatching { scanner(dao, max = 1, page = 2).inspect() }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(0, dao.writes)
    }

    private class FakeDao(val rows: MutableList<AssetEntity> = mutableListOf()) : AssetDao {
        var writes: Int = 0
        override suspend fun insert(asset: AssetEntity) { writes++; rows.add(asset) }
        override suspend fun findById(id: String): AssetEntity? = rows.firstOrNull { it.id == id }
        override suspend fun findBySha256(sha256: String): AssetEntity? = rows.firstOrNull { it.sha256 == sha256 }
        override suspend fun findByRelativePath(relativePath: String): AssetEntity? =
            rows.firstOrNull { it.relativePath == relativePath }
        override suspend fun listByState(state: String, limit: Int): List<AssetEntity> =
            rows.filter { it.state == state }.take(limit)
        override suspend fun countAll(): Int = rows.size
        override suspend fun listAfterId(afterId: String?, limit: Int): List<AssetEntity> =
            rows.filter { afterId == null || it.id > afterId }.sortedBy { it.id }.take(limit)
    }
}
