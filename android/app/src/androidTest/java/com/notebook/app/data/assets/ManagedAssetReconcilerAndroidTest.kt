package com.notebook.app.data.assets

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only recovery comparison on an isolated on-device Room database and private directory. */
@RunWith(AndroidJUnit4::class)
class ManagedAssetReconcilerAndroidTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var root: File
    private var db: AppDatabase? = null
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x00,
    )

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        name = "sto04-reconciler-$suffix.sqlite"
        root = File(context.filesDir, "sto04-reconciler-$suffix")
        assertTrue(root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, name)
    }

    @After fun teardown() {
        db?.close()
        context.deleteDatabase(name)
        if (::root.isInitialized && root.name.startsWith("sto04-reconciler-")) root.deleteRecursively()
    }

    @Test fun registeredAndOrphanFinalsAreDistinguished() = runBlocking {
        val localDb = requireNotNull(db)
        val registered = ManagedAssetRegistrar(ManagedAssetFileStore(root), localDb.assetDao())
            .register(ByteArrayInputStream(png), "image/png")
        val first = ManagedAssetReconciler(root, localDb.assetDao()).inspect()
        assertEquals(1, first.size)
        assertEquals(ManagedAssetReconciler.Kind.VERIFIED_REGISTERED, first.single().kind)

        // Remove no DB data. Place another valid *unregistered* final using the file store.
        val other = png + byteArrayOf(1)
        val orphan = ManagedAssetFileStore(root).stageAndFinalize(ByteArrayInputStream(other), "image/png")
        val second = ManagedAssetReconciler(root, localDb.assetDao()).inspect()
        assertEquals(2, second.size)
        assertEquals(1, second.count { it.kind == ManagedAssetReconciler.Kind.VERIFIED_REGISTERED })
        assertEquals(1, second.count { it.kind == ManagedAssetReconciler.Kind.VALID_UNREGISTERED_FINAL })
        assertTrue(File(root, orphan.relativePath).exists())
        assertEquals(1, localDb.assetDao().countAll())
        assertTrue(File(root, registered.relativePath).exists())
    }

    @Test fun missingRegisteredFinalIsReportedWithoutChangingDatabase() = runBlocking {
        val localDb = requireNotNull(db)
        val registered = ManagedAssetRegistrar(ManagedAssetFileStore(root), localDb.assetDao())
            .register(ByteArrayInputStream(png), "image/png")
        // Isolated fixture only: deliberately simulate a missing file after registration.
        assertTrue(File(root, registered.relativePath).delete())
        val findings = ManagedAssetReconciler(root, localDb.assetDao()).inspect()
        assertEquals(1, findings.size)
        assertEquals(ManagedAssetReconciler.Kind.MISSING_OR_UNSCANNED_FINAL, findings.single().kind)
        assertEquals(registered, localDb.assetDao().findById(registered.id))
    }
}
