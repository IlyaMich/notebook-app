package com.notebook.app.data.assets

import com.notebook.app.data.local.dao.AssetDao
import com.notebook.app.data.local.entity.AssetEntity
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedAssetRegistrarTest {
    @get:Rule val temp = TemporaryFolder()

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x00,
    )

    private fun store() = ManagedAssetFileStore(temp.root, directorySync = {})
    private fun input() = ByteArrayInputStream(png)

    @Test fun registrationOccursAfterFinalizationAndPersistsMatchingMetadata() = runBlocking {
        val dao = FakeAssetDao()
        val result = ManagedAssetRegistrar(store(), dao, newId = { "first-id" }).register(input(), "image/png")
        assertEquals("first-id", result.id)
        assertEquals("finalized", result.state)
        assertEquals(png.size.toLong(), result.byteSize)
        assertEquals(result, dao.findBySha256(result.sha256))
        assertArrayEquals(png, temp.root.resolve(result.relativePath).readBytes())
        assertEquals(1, dao.insertAttempts)
    }

    @Test fun repeatedImportReusesStableIdWithoutInsertingAgain() = runBlocking {
        val dao = FakeAssetDao()
        val first = ManagedAssetRegistrar(store(), dao, newId = { "first-id" }).register(input(), "image/png")
        val second = ManagedAssetRegistrar(store(), dao, newId = { "second-id" }).register(input(), "image/png")
        assertEquals(first, second)
        assertEquals(1, dao.insertAttempts)
        assertEquals(1, dao.rows.size)
    }

    @Test fun invalidInputDoesNotCallDaoOrRegisterMetadata() = runBlocking {
        val dao = FakeAssetDao()
        assertFails<IOException> {
            ManagedAssetRegistrar(store(), dao).register(input(), "application/pdf")
        }
        assertEquals(0, dao.insertAttempts)
        assertEquals(0, dao.rows.size)
    }

    @Test fun databaseFailureLeavesVerifiedUnreferencedFileForRecovery() = runBlocking {
        val dao = FakeAssetDao()
        dao.onInsert = { throw IOException("simulated Room write failure") }
        assertFails<IOException> { ManagedAssetRegistrar(store(), dao).register(input(), "image/png") }
        assertEquals(0, dao.rows.size)
        assertEquals(1, temp.root.resolve("assets").walkTopDown().count { it.isFile })
        dao.onInsert = null
        val retry = ManagedAssetRegistrar(store(), dao).register(input(), "image/png")
        assertArrayEquals(png, temp.root.resolve(retry.relativePath).readBytes())
        assertEquals(1, dao.rows.size)
    }

    @Test fun conflictingPreexistingMetadataIsRejectedWithoutMutation() = runBlocking {
        val dao = FakeAssetDao()
        val finalized = store().stageAndFinalize(input(), "image/png")
        val conflicting = AssetEntity("old-id", finalized.sha256, finalized.mimeType,
            finalized.byteSize + 1, finalized.relativePath, "finalized")
        dao.rows.add(conflicting)
        assertFails<IOException> { ManagedAssetRegistrar(store(), dao).register(input(), "image/png") }
        assertEquals(conflicting, dao.rows.single())
        assertEquals(0, dao.insertAttempts)
        assertArrayEquals(png, temp.root.resolve(finalized.relativePath).readBytes())
    }

    @Test fun competingMatchingRowAfterInitialLookupIsReused() = runBlocking {
        val dao = FakeAssetDao()
        dao.onInsert = { candidate ->
            dao.rows.add(candidate.copy(id = "competing-id"))
            throw IOException("simulated uniqueness race")
        }
        val result = ManagedAssetRegistrar(store(), dao).register(input(), "image/png")
        assertEquals("competing-id", result.id)
        assertEquals(1, dao.rows.size)
        assertEquals(1, dao.insertAttempts)
    }

    @Test fun competingConflictingRowIsRejectedNotReportedAsSuccess() = runBlocking {
        val dao = FakeAssetDao()
        dao.onInsert = { candidate ->
            dao.rows.add(candidate.copy(id = "competing-id", state = "quarantined"))
            throw IOException("simulated uniqueness race")
        }
        assertFails<IOException> { ManagedAssetRegistrar(store(), dao).register(input(), "image/png") }
        assertEquals("quarantined", dao.rows.single().state)
    }

    @Test fun failedReadAfterWriteFailurePreservesOriginalFailure() = runBlocking {
        val dao = FakeAssetDao()
        dao.onInsert = { throw IOException("original write failure") }
        dao.failLookupAfterInsert = true
        val failure = assertFails<IOException> {
            ManagedAssetRegistrar(store(), dao).register(input(), "image/png")
        }
        assertTrue(failure.message!!.contains("original write failure"))
        assertEquals(1, failure.suppressed.size)
        assertEquals(0, dao.rows.size)
    }

    @Test fun corruptExistingFinalIsNeverRegisteredAgain() = runBlocking {
        val dao = FakeAssetDao()
        val first = ManagedAssetRegistrar(store(), dao).register(input(), "image/png")
        temp.root.resolve(first.relativePath).writeBytes(byteArrayOf(1, 2, 3))
        assertFails<IOException> { ManagedAssetRegistrar(store(), dao).register(input(), "image/png") }
        assertEquals(1, dao.rows.size)
        assertEquals(1, dao.insertAttempts)
    }

    private suspend inline fun <reified T : Throwable> assertFails(noinline action: suspend () -> Unit): T {
        try {
            action()
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
            return failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private class FakeAssetDao : AssetDao {
        val rows = mutableListOf<AssetEntity>()
        var insertAttempts = 0
        var onInsert: ((AssetEntity) -> Unit)? = null
        var failLookupAfterInsert = false
        override suspend fun insert(asset: AssetEntity) {
            insertAttempts++
            onInsert?.invoke(asset)
            check(rows.none { it.id == asset.id || it.sha256 == asset.sha256 || it.relativePath == asset.relativePath })
            rows.add(asset)
        }
        override suspend fun findById(id: String): AssetEntity? = rows.firstOrNull { it.id == id }
        override suspend fun findBySha256(sha256: String): AssetEntity? {
            if (failLookupAfterInsert && insertAttempts > 0) throw IOException("lookup failure")
            return rows.firstOrNull { it.sha256 == sha256 }
        }
        override suspend fun findByRelativePath(relativePath: String): AssetEntity? =
            rows.firstOrNull { it.relativePath == relativePath }
        override suspend fun listByState(state: String, limit: Int): List<AssetEntity> =
            rows.filter { it.state == state }.take(limit)
        override suspend fun listAfterId(afterId: String?, limit: Int): List<AssetEntity> =
            rows.filter { afterId == null || it.id > afterId }.sortedBy { it.id }.take(limit)
        override suspend fun countAll(): Int = rows.size
    }
}
