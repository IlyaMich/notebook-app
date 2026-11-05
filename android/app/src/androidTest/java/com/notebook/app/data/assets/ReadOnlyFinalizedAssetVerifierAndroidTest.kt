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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** C2C-2B only: isolated Room database and uniquely named disposable test files. */
@RunWith(AndroidJUnit4::class)
class ReadOnlyFinalizedAssetVerifierAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var root: File
    private var db: AppDatabase? = null

    // Signature-only fixture: these tests establish byte integrity, NOT complete image decoding.
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x00,
    )

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "sto08-c2c2b-verifier-$suffix.sqlite"
        root = File(context.filesDir, "sto08-c2c2b-verifier-$suffix")
        assertTrue("Unable to create isolated asset root", root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, databaseName)
    }

    @After fun tearDown() {
        db?.close()
        db = null
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
        // Only this test's newly generated directory may be removed. Never touch app originals.
        if (::root.isInitialized && root.name.startsWith("sto08-c2c2b-verifier-")) {
            root.deleteRecursively()
        }
    }

    private fun liveDb(): AppDatabase = requireNotNull(db)

    private suspend fun register(): AssetEntity =
        ManagedAssetRegistrar(ManagedAssetFileStore(root), liveDb().assetDao())
            .register(ByteArrayInputStream(png), "image/png")

    private suspend fun expectIOException(assetId: String) {
        val failure = runCatching {
            ReadOnlyFinalizedAssetVerifier(root, liveDb().assetDao()).verify(assetId)
        }.exceptionOrNull()
        assertTrue("Expected IOException, got $failure", failure is IOException)
    }

    @Test fun registeredFinalSurvivesReopenAndVerificationDoesNotWrite() = runBlocking {
        val originalDb = liveDb()
        val asset = register()
        val originalBytes = File(root, asset.relativePath).readBytes()
        assertEquals("UNVERIFIED", originalDb.assetOwnershipDao().readFailClosedGateStatus())
        originalDb.close()
        db = null

        val reopened = AppDatabaseProvider.openIsolated(context, databaseName)
        db = reopened
        val snapshot = ReadOnlyFinalizedAssetVerifier(root, reopened.assetDao()).verify(asset.id)
        assertEquals(asset.id, snapshot.assetId)
        assertEquals(asset.sha256, snapshot.sha256)
        assertEquals(asset.mimeType, snapshot.mimeType)
        assertEquals(asset.byteSize, snapshot.byteSize)
        assertEquals(asset, reopened.assetDao().findById(asset.id))
        assertEquals(1, reopened.assetDao().countAll())
        assertEquals("UNVERIFIED", reopened.assetOwnershipDao().readFailClosedGateStatus())
        assertArrayEquals(originalBytes, File(root, asset.relativePath).readBytes())
    }

    @Test fun missingFinalRejectsAndPreservesRegisteredMetadata() = runBlocking {
        val asset = register()
        val finalFile = File(root, asset.relativePath)
        assertTrue(finalFile.delete()) // Only this isolated test file is removed.
        expectIOException(asset.id)
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals(1, liveDb().assetDao().countAll())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun sameLengthCorruptionRejectsWithoutRepairingBytesOrMetadata() = runBlocking {
        val asset = register()
        val finalFile = File(root, asset.relativePath)
        val tampered = ByteArray(png.size) { 0x55.toByte() }
        finalFile.writeBytes(tampered) // Controlled isolated fault injection.
        expectIOException(asset.id)
        assertArrayEquals(tampered, finalFile.readBytes())
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun symlinkedFinalRejectsWithoutFollowingTarget() = runBlocking {
        val asset = register()
        val finalFile = File(root, asset.relativePath)
        val linkTarget = File(root, "isolated-link-target.png")
        linkTarget.writeBytes(png)
        assertTrue(finalFile.delete()) // Only this isolated test file is replaced.
        Files.createSymbolicLink(finalFile.toPath(), linkTarget.toPath())
        expectIOException(asset.id)
        assertTrue(Files.isSymbolicLink(finalFile.toPath()))
        assertArrayEquals(png, linkTarget.readBytes())
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun conflictingMetadataStateRejectsWithoutEditingRoom() = runBlocking {
        val file = ManagedAssetFileStore(root)
            .stageAndFinalize(ByteArrayInputStream(png), "image/png")
        val conflicting = AssetEntity(
            id = UUID.randomUUID().toString(),
            sha256 = file.sha256,
            mimeType = file.mimeType,
            byteSize = file.byteSize,
            relativePath = file.relativePath,
            state = "quarantined",
        )
        liveDb().assetDao().insert(conflicting)
        expectIOException(conflicting.id)
        assertEquals(conflicting, liveDb().assetDao().findById(conflicting.id))
        assertArrayEquals(png, File(root, file.relativePath).readBytes())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun unknownIdRejectsWithoutCreatingAssetOrChangingGate() = runBlocking {
        assertEquals(0, liveDb().assetDao().countAll())
        expectIOException("missing-asset-id")
        assertEquals(0, liveDb().assetDao().countAll())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
    }
}
