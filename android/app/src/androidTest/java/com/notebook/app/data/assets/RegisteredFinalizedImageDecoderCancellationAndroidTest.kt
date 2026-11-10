package com.notebook.app.data.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only I3 cancellation probes. No attachment writer or owner mutation. */
@RunWith(AndroidJUnit4::class)
class RegisteredFinalizedImageDecoderCancellationAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var root: File
    private var db: AppDatabase? = null

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "sto08-c2c3b-i3-cancel-$suffix.sqlite"
        root = File(context.filesDir, "sto08-c2c3b-i3-cancel-$suffix")
        assertTrue("Cannot create cancellation-test root", root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, databaseName)
    }

    @After fun tearDown() {
        db?.close()
        db = null
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
        if (::root.isInitialized && root.name.startsWith("sto08-c2c3b-i3-cancel-")) {
            root.deleteRecursively()
        }
    }

    private fun liveDb(): AppDatabase = requireNotNull(db)

    private fun realPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(38, 102, 189))
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun register(bytes: ByteArray): AssetEntity =
        ManagedAssetRegistrar(ManagedAssetFileStore(root), liveDb().assetDao())
            .register(ByteArrayInputStream(bytes), "image/png")

    private suspend fun assertUnchanged(asset: AssetEntity, original: ByteArray) {
        assertArrayEquals(original, File(root, asset.relativePath).readBytes())
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals(1, liveDb().assetDao().countAll())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
        assertEquals("released", AssetAcquisitionLeaseCoordinator(root).withLease { "released" })
    }

    @Test fun preCancelledRegisteredAssetCannotReturnMetadataOrMutateOwnership() = runBlocking {
        val original = realPng()
        val asset = register(original)
        val cancelledJob = Job().apply { cancel() }
        val error = runCatching {
            runBlocking(cancelledJob) {
                RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
            }
        }.exceptionOrNull()
        assertTrue("Expected CancellationException, got $error", error is CancellationException)
        assertUnchanged(asset, original)
    }

    @Test fun cancellationWhileWaitingForAssetLeaseReleasesResources() = runBlocking {
        val original = realPng()
        val asset = register(original)
        val decoder = RegisteredFinalizedImageDecoder(root, liveDb().assetDao())
        AssetAcquisitionLeaseCoordinator(root).withLease {
            val blockedInspection = async(Dispatchers.IO) { decoder.inspect(asset.id) }
            delay(150L)
            assertFalse("Inspector must not bypass active publication lease", blockedInspection.isCompleted)
            blockedInspection.cancel()
            val error = runCatching { blockedInspection.await() }.exceptionOrNull()
            assertTrue("Expected cancellation while awaiting lease, got $error", error is CancellationException)
        }
        assertUnchanged(asset, original)
        val result = decoder.inspect(asset.id)
        assertEquals(asset.id, result.assetId)
        assertEquals(8, result.width)
        assertEquals(6, result.height)
        assertUnchanged(asset, original)
    }
}
