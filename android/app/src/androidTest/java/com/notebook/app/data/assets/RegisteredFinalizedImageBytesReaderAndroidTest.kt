package com.notebook.app.data.assets

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** I1 tests the actual Android symlink boundary. No bitmap decode or owner mutation. */
@RunWith(AndroidJUnit4::class)
class RegisteredFinalizedImageBytesReaderAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var root: File
    private var db: AppDatabase? = null

    // I1 signature fixture only; full-image decoding is an I2/I3 requirement.
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x00,
    )

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "sto08-c2c3b-i1-reader-$suffix.sqlite"
        root = File(context.filesDir, "sto08-c2c3b-i1-reader-$suffix")
        assertTrue("Cannot create isolated asset root", root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, databaseName)
    }

    @After fun tearDown() {
        db?.close()
        db = null
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
        // Only the isolated test sandbox may be removed.
        if (::root.isInitialized && root.name.startsWith("sto08-c2c3b-i1-reader-")) {
            root.deleteRecursively()
        }
    }

    private fun liveDb(): AppDatabase = requireNotNull(db)

    private suspend fun register(): AssetEntity =
        ManagedAssetRegistrar(ManagedAssetFileStore(root), liveDb().assetDao())
            .register(ByteArrayInputStream(png), "image/png")

    @Test fun symlinkedOriginalRejectsWithoutReadingOrModifyingTarget() = runBlocking {
        val asset = register()
        val finalFile = File(root, asset.relativePath)
        val target = File(root, "isolated-symlink-target.png")
        target.writeBytes(png)
        assertTrue(finalFile.delete()) // Replace only our own registered original.
        Files.createSymbolicLink(finalFile.toPath(), target.toPath())
        assertTrue(Files.isSymbolicLink(finalFile.toPath()))

        var callbackCalled = false
        val failure = runCatching {
            RegisteredFinalizedImageBytesReader(root, liveDb().assetDao())
                .withVerifiedBytes(asset.id) { _, _ -> callbackCalled = true }
        }.exceptionOrNull()

        assertTrue("Expected IOException, got $failure", failure is IOException)
        assertFalse("Linked original reached the callback", callbackCalled)
        assertArrayEquals(png, target.readBytes())
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
        assertEquals("released", AssetAcquisitionLeaseCoordinator(root).withLease { "released" })
    }

    @Test fun registeredImageDeliversEphemeralBytesAndDoesNotMutateRoom() = runBlocking {
        val asset = register()
        var captured: ByteArray? = null
        val returned = RegisteredFinalizedImageBytesReader(root, liveDb().assetDao())
            .withVerifiedBytes(asset.id) { snapshot, bytes ->
                assertEquals(asset.id, snapshot.assetId)
                assertEquals("image/png", snapshot.mimeType)
                assertArrayEquals(png, bytes)
                captured = bytes
                bytes.size
            }

        assertEquals(png.size, returned)
        assertTrue(requireNotNull(captured).all { it == 0.toByte() })
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals(1, liveDb().assetDao().countAll())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
    }
}
