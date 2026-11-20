package com.notebook.app.data.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.entity.ElementAssetReferenceEntity
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room, files, Android fsync and shared OS lease; isolated disposable test data only. */
@RunWith(AndroidJUnit4::class)
class ExportRetainedPrivateCopyAndroidTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var name: String
    private lateinit var root: File
    private lateinit var noteId: String
    private lateinit var pageId: String

    private fun id(): String = UUID.randomUUID().toString()
    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    private val snapLimits = SnapshotLimits(4, 16, 4, 100_000, 400_000, 100_000, 4, 16)
    private val copyLimits = ExportCopyAdmissionLimits(4, 100_000, 400_000)
    private val stageLimits = ExportPrivateStageLimits(4, 100_000, 400_000, 100_000, 8)

    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "sto08-d2b-" + id() + ".sqlite"
        check(name != AppDatabaseProvider.DATABASE_NAME)
        root = File(context.filesDir, "sto08-d2b-private-" + id())
        assertTrue(root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, name)
        val book = id()
        noteId = id()
        pageId = id()
        db.notebookDao().insert(NotebookEntity(book, null, "Test", sortOrder = 0L,
            createdAtMillis = 1L, updatedAtMillis = 1L))
        db.noteDao().insert(NoteEntity(noteId, book, "Retention test", 1L, 1L, revision = 2L))
        db.pageDao().insert(PageEntity(pageId, noteId, 0L, 600.0, 800.0,
            """{"type":"blank","color":"#FFFFFF"}""", 1L))
    }

    @After fun teardown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::name.isInitialized) context.deleteDatabase(name)
        if (::root.isInitialized) assertTrue("Could not remove isolated fixture", root.deleteRecursively())
    }

    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(19, 105, 171))
            ByteArrayOutputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 95, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private suspend fun registerImage(bytes: ByteArray): Pair<String, File> {
        val digest = sha(bytes)
        val assetId = id()
        val relative = "assets/" + digest.take(2) + "/" + digest.substring(2, 4) + "/" +
            digest + ".png"
        val source = File(root, relative)
        assertTrue(source.parentFile!!.mkdirs())
        source.writeBytes(bytes)
        db.assetDao().insert(AssetEntity(assetId, digest, "image/png",
            bytes.size.toLong(), relative, "finalized"))
        val element = id()
        val json = """{"id":"$element","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""
        db.elementDao().insert(ElementEntity(element, pageId, 0, "image", json, 1L))
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(element, assetId))
        return assetId to source
    }

    private fun coordinator(): ExportRetainedPrivateCopyCoordinator =
        ExportHoldAdmissionComposition.retainedPrivateCopyForRoom(root, db, stageLimits)

    @Test fun capturesHoldsCopiesAndManifestWithoutTouchingOriginal() = runBlocking {
        val bytes = imageBytes()
        val (asset, original) = registerImage(bytes)
        val operation = id()
        val outcome = coordinator().stage(operation, SnapshotRequest(noteId, snapLimits),
            copyLimits, 123L)
        assertTrue(outcome is ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted)
        val observed = (outcome as ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted).observation
        assertEquals(noteId, observed.noteId)
        assertEquals(2L, observed.noteRevision)
        assertEquals(listOf(asset), observed.copies.map { it.assetId })
        assertEquals(listOf(asset), db.exportHoldAdmissionDao().listHolds(operation).map { it.assetId })
        val stageDir = File(root, "pending-export-copies/$operation")
        assertArrayEquals(bytes, File(stageDir, "asset-$asset.bin").readBytes())
        assertTrue(File(stageDir, "snapshot.json").readText().contains(noteId))
        assertTrue(File(stageDir, "manifest.json").readText().contains(asset))
        assertFalse(File(stageDir, "receipt").exists())
        assertArrayEquals(bytes, original.readBytes())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun missingOriginalAfterHoldCommitFailsButRetainsPins() = runBlocking {
        val bytes = imageBytes()
        val (asset, original) = registerImage(bytes)
        assertTrue(original.delete()) // ONLY isolated test-original, never application library
        val operation = id()
        val failure = runCatching {
            coordinator().stage(operation, SnapshotRequest(noteId, snapLimits),
                copyLimits, 123L)
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(listOf(asset), db.exportHoldAdmissionDao().listHolds(operation).map { it.assetId })
        assertTrue(File(root, "pending-export-copies/$operation").exists())
        assertFalse(File(root, "pending-export-copies/$operation/manifest.json").exists())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun trashedNoteRejectsBeforeCreatingPinsOrStage() = runBlocking {
        val bytes = imageBytes()
        val (_, original) = registerImage(bytes)
        val note = db.noteDao().findById(noteId)!!
        db.noteDao().update(note.copy(deletedAtMillis = 7L))
        val operation = id()
        assertEquals(
            ExportRetainedPrivateCopyCoordinator.Result.Rejected(SnapshotRejection.INELIGIBLE_NOTE),
            coordinator().stage(operation, SnapshotRequest(noteId, snapLimits), copyLimits, 123L),
        )
        assertNull(db.exportHoldAdmissionDao().findOperation(operation))
        assertFalse(File(root, "pending-export-copies/$operation").exists())
        assertArrayEquals(bytes, original.readBytes())
    }
}
