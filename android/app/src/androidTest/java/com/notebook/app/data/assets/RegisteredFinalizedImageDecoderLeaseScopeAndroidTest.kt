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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** C2C-3C-I1: callback lease lifetime only. No attachment/ownership mutations. */
@RunWith(AndroidJUnit4::class)
class RegisteredFinalizedImageDecoderLeaseScopeAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var root: File
    private var db: AppDatabase? = null

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "sto08-c2c3c-i1-lease-$suffix.sqlite"
        root = File(context.filesDir, "sto08-c2c3c-i1-lease-$suffix")
        assertTrue("Cannot create isolated root", root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, databaseName)
    }

    @After fun tearDown() {
        db?.close()
        db = null
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
        if (::root.isInitialized && root.name.startsWith("sto08-c2c3c-i1-lease-")) {
            assertTrue("Cannot remove isolated root", root.deleteRecursively())
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

    private suspend fun assertUnchanged(asset: AssetEntity, bytes: ByteArray) {
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertArrayEquals(bytes, File(root, asset.relativePath).readBytes())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun callbackHoldsLeaseAcrossSuspensionAndReturnsMetadataOnly() = runBlocking {
        val bytes = realPng()
        val asset = register(bytes)
        val entered = CompletableDeferred<VerifiedDecodedImageInfo>()
        val release = CompletableDeferred<Unit>()
        val contenderEntered = CompletableDeferred<Unit>()
        val decoder = RegisteredFinalizedImageDecoder(root, liveDb().assetDao())
        val holder = async(Dispatchers.IO) {
            decoder.withInspectedAssetLease(asset.id) { info ->
                entered.complete(info)
                release.await()
                "callback-complete"
            }
        }
        try {
            val info = withTimeout(5_000L) { entered.await() }
            assertEquals(asset.id, info.assetId)
            assertEquals(8, info.width)
            assertEquals(6, info.height)
            val contender = async(Dispatchers.IO) {
                AssetAcquisitionLeaseCoordinator(root).withLease {
                    contenderEntered.complete(Unit)
                }
            }
            try {
                assertNull(withTimeoutOrNull(200L) { contenderEntered.await(); true })
                assertFalse("Competing lease must be blocked", contenderEntered.isCompleted)
                release.complete(Unit)
                assertEquals("callback-complete", withTimeout(5_000L) { holder.await() })
                withTimeout(5_000L) { contender.await() }
                assertTrue(contenderEntered.isCompleted)
            } finally {
                release.complete(Unit)
                contender.cancelAndJoin()
            }
        } finally {
            release.complete(Unit)
            holder.cancelAndJoin()
        }
        assertUnchanged(asset, bytes)
    }

    @Test fun callbackExceptionReleasesLeaseWithoutOwnerWrites() = runBlocking {
        val bytes = realPng()
        val asset = register(bytes)
        val marker = IllegalStateException("intentional-test-failure")
        val decoder = RegisteredFinalizedImageDecoder(root, liveDb().assetDao())
        val thrown = runCatching {
            decoder.withInspectedAssetLease(asset.id) { _: VerifiedDecodedImageInfo ->
                throw marker
            }
        }.exceptionOrNull()
        assertTrue("Callback failure must propagate unchanged", thrown === marker)
        assertEquals("released", withTimeout(5_000L) {
            AssetAcquisitionLeaseCoordinator(root).withLease { "released" }
        })
        assertUnchanged(asset, bytes)
    }

    @Test fun callbackCancellationReleasesLeaseWithoutOwnerWrites() = runBlocking {
        val bytes = realPng()
        val asset = register(bytes)
        val entered = CompletableDeferred<Unit>()
        val decoder = RegisteredFinalizedImageDecoder(root, liveDb().assetDao())
        val holder = async(Dispatchers.IO) {
            decoder.withInspectedAssetLease(asset.id) { _: VerifiedDecodedImageInfo ->
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            withTimeout(5_000L) { entered.await() }
        } finally {
            holder.cancelAndJoin()
        }
        assertTrue("Expected cancellation", holder.isCancelled)
        assertEquals("released", withTimeout(5_000L) {
            AssetAcquisitionLeaseCoordinator(root).withLease { "released" }
        })
        assertUnchanged(asset, bytes)
    }
}
