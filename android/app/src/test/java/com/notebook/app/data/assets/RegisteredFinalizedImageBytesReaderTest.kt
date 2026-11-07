package com.notebook.app.data.assets

import com.notebook.app.data.local.dao.AssetDao
import com.notebook.app.data.local.entity.AssetEntity
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** I1 tests use signatures only. I2 must prove real bitmap decoding on Android. */
class RegisteredFinalizedImageBytesReaderTest {
    @get:Rule val temp = TemporaryFolder()

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0,
    )
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 1, 2, 3)

    private fun row(bytes: ByteArray, mime: String = "image/png"): AssetEntity {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val ext = if (mime == "image/jpeg") "jpg" else "png"
        return AssetEntity(
            "asset-id", sha, mime, bytes.size.toLong(),
            "assets/${sha.substring(0, 2)}/${sha.substring(2, 4)}/$sha.$ext", "finalized",
        )
    }

    private fun install(asset: AssetEntity, bytes: ByteArray): File {
        val file = File(temp.root, asset.relativePath)
        assertTrue(file.parentFile!!.mkdirs())
        file.writeBytes(bytes)
        return file
    }

    private fun reader(dao: FakeDao): RegisteredFinalizedImageBytesReader =
        RegisteredFinalizedImageBytesReader(temp.root, dao)

    private suspend fun expectIOException(dao: FakeDao, id: String = "asset-id") {
        var called = false
        val failure = runCatching {
            reader(dao).withVerifiedBytes(id) { _, _ -> called = true }
        }.exceptionOrNull()
        assertTrue("Expected IOException; got $failure", failure is IOException)
        assertFalse("Rejected input reached the callback", called)
        assertEquals(0, dao.writes)
    }

    @Test fun registeredPngBytesAreVerifiedUnderLeaseAndWipedAfterCallback() = runBlocking {
        val asset = row(png)
        val file = install(asset, png)
        val dao = FakeDao(asset)
        var captured: ByteArray? = null
        val returned = reader(dao).withVerifiedBytes("asset-id") { snapshot, bytes ->
            assertEquals(asset.id, snapshot.assetId)
            assertEquals(asset.sha256, snapshot.sha256)
            assertArrayEquals(png, bytes)
            assertTrue(File(temp.root, ".asset-publication.lock").isFile)
            captured = bytes
            bytes.size
        }
        assertEquals(png.size, returned)
        assertTrue(requireNotNull(captured).all { it == 0.toByte() })
        assertArrayEquals(png, file.readBytes())
        assertEquals(0, dao.writes)
    }

    @Test fun registeredJpegBytesAreVerifiedWithoutMimeNormalization() = runBlocking {
        val asset = row(jpeg, "image/jpeg")
        install(asset, jpeg)
        val dao = FakeDao(asset)
        reader(dao).withVerifiedBytes("asset-id") { snapshot, bytes ->
            assertEquals("image/jpeg", snapshot.mimeType)
            assertArrayEquals(jpeg, bytes)
        }
        assertEquals(0, dao.writes)
    }

    @Test fun absentUnfinalizedOrOversizedMetadataIsRejected() = runBlocking {
        expectIOException(FakeDao(null))
        val asset = row(png)
        install(asset, png)
        expectIOException(FakeDao(asset.copy(state = "quarantined")))
        expectIOException(FakeDao(asset.copy(byteSize = 16_777_217L)))
        expectIOException(FakeDao(asset.copy(byteSize = 0L)))
        expectIOException(FakeDao(asset.copy(mimeType = "application/pdf")))
    }

    @Test fun invalidPathsAndDigestsAreRejectedWithoutTouchingOriginal() = runBlocking {
        val asset = row(png)
        val file = install(asset, png)
        expectIOException(FakeDao(asset.copy(relativePath = "../outside.png")))
        expectIOException(FakeDao(asset.copy(sha256 = "broken")))
        assertArrayEquals(png, file.readBytes())
    }

    @Test fun sameLengthMutationAndMismatchedLengthAreRejected() = runBlocking {
        val asset = row(png)
        val file = install(asset, png)
        val dao = FakeDao(asset)
        file.writeBytes(png.copyOf().also { it[it.lastIndex] = 0x72.toByte() })
        expectIOException(dao)
        file.writeBytes(png.copyOfRange(0, png.size - 1))
        expectIOException(dao)
    }

    @Test fun matchingDigestCannotOverrideTheImageSignatureRequirement() = runBlocking {
        val fakePng = byteArrayOf(0x41, 0x42, 0x43, 0x44)
        val asset = row(fakePng)
        val file = install(asset, fakePng)
        expectIOException(FakeDao(asset))
        assertArrayEquals(fakePng, file.readBytes())
    }

    // Windows JVMs may lack permission to create symlinks (error 1314). Keep this
    // platform-independent missing-file check; the symlink case is mandatory in
    // RegisteredFinalizedImageBytesReaderAndroidTest on the approved disposable AVD.
    @Test fun missingOriginalIsRejectedWithoutMutatingMetadata() = runBlocking {
        val asset = row(png)
        val file = install(asset, png)
        val dao = FakeDao(asset)
        assertTrue(file.delete())
        expectIOException(dao)
        assertFalse(Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals(0, dao.writes)
    }

    @Test fun leaseRemainsHeldForEntireCallback() = runBlocking {
        val asset = row(png)
        install(asset, png)
        val dao = FakeDao(asset)
        val contender = AssetAcquisitionLeaseCoordinator(temp.root, 100L, 10L)
        reader(dao).withVerifiedBytes("asset-id") { _, _ ->
            val error = runCatching { contender.withLease { "unexpected acquisition" } }.exceptionOrNull()
            assertTrue("Expected lock acquisition timeout, got $error", error is TimeoutCancellationException)
        }
        assertEquals("released", contender.withLease { "released" })
    }

    @Test fun callbackExceptionReleasesLeaseAndClearsEphemeralBytes() = runBlocking {
        val asset = row(png)
        install(asset, png)
        val dao = FakeDao(asset)
        var captured: ByteArray? = null
        val error = runCatching {
            reader(dao).withVerifiedBytes("asset-id") { _, bytes ->
                captured = bytes
                throw IllegalStateException("controlled callback failure")
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(requireNotNull(captured).all { it == 0.toByte() })
        assertEquals("released", AssetAcquisitionLeaseCoordinator(temp.root).withLease { "released" })
        assertEquals(0, dao.writes)
    }

    private class FakeDao(private val asset: AssetEntity?) : AssetDao {
        var writes = 0
        override suspend fun insert(asset: AssetEntity) { writes++ }
        override suspend fun findById(id: String): AssetEntity? = asset?.takeIf { it.id == id }
        override suspend fun findBySha256(sha256: String): AssetEntity? = asset?.takeIf { it.sha256 == sha256 }
        override suspend fun findByRelativePath(relativePath: String): AssetEntity? =
            asset?.takeIf { it.relativePath == relativePath }
        override suspend fun listByState(state: String, limit: Int): List<AssetEntity> =
            listOfNotNull(asset).filter { it.state == state }.take(limit)
        override suspend fun listAfterId(afterId: String?, limit: Int): List<AssetEntity> =
            listOfNotNull(asset).filter { afterId == null || it.id > afterId }.take(limit)
        override suspend fun countAll(): Int = if (asset == null) 0 else 1
    }
}
