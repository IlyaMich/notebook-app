package com.notebook.app.data.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.assets.AssetAcquisitionLeaseCoordinator
import com.notebook.app.data.assets.ManagedAssetFileStore
import com.notebook.app.data.assets.ManagedAssetRegistrar
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.ImageFrame
import com.notebook.app.data.content.model.InsertVerifiedImageCommand
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 C2C-3C-I3 process-death acceptance, acknowledged-image phase only.
 *
 * HOST-DRIVEN, TWO INVOCATIONS: `-e sto08PdPhase ack-prepare` then an externally
 * terminated target process, then `-e sto08PdPhase ack-verify` in a NEW process.
 * Ordinary Gradle runs skip both tests and never kill a process.
 *
 * The host must verify the READY_TO_KILL marker and its target PID, then force-stop
 * only the approved disposable emulator's com.notebook.app package. Never clear app
 * data, reinstall APKs between phases, or invoke these methods on a real device.
 *
 * The isolated database and UUID-scoped asset directory are not library.sqlite.
 * We intentionally do NOT add an application-facing test hook or modify the writer.
 */
@RunWith(AndroidJUnit4::class)
class InsertVerifiedImageProcessDeathAndroidTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prefs
        get() = context.getSharedPreferences("sto08-pd-ack-fixture", Context.MODE_PRIVATE)

    private fun onlyPhase(phase: String) {
        assumeTrue(
            "STO-08 process-death test requires the explicit host ADB driver",
            InstrumentationRegistry.getArguments().getString("sto08PdPhase") == phase,
        )
    }

    private fun id(): String = UUID.randomUUID().toString()

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(38, 102, 189))
            ByteArrayOutputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun saved(key: String): String =
        prefs.getString(key, null) ?: error("Missing process-death fixture key: $key")

    private fun verifiedRoot(name: String): File {
        require(Regex("sto08-pd-ack-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            .matches(name)) { "Unsafe fixture root name" }
        return File(context.filesDir, name).also { root ->
            check(root.parentFile!!.canonicalFile == context.filesDir.canonicalFile)
            check(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
        }
    }

    @Test
    fun prepareAcknowledgedImageAndAwaitExternalProcessDeath() {
        onlyPhase("ack-prepare")
        check(!prefs.contains("databaseName")) {
            "Existing STO-08 process-death fixture: preserve for diagnosis; do not overwrite"
        }
        val suffix = id()
        val rootName = "sto08-pd-ack-$suffix"
        val databaseName = "sto08-pd-ack-$suffix.sqlite"
        val root = File(context.filesDir, rootName)
        assertFalse(root.exists())
        assertTrue("Failed to create isolated image root", root.mkdir())

        val notebookId = id()
        val noteId = id()
        val pageId = id()
        val otherNoteId = id()
        val otherPageId = id()
        val elementId = id()
        val db = AppDatabaseProvider.openIsolated(context, databaseName)
        val asset = runBlocking {
            db.openHelper.writableDatabase
            db.notebookDao().insert(NotebookEntity(
                notebookId, null, "STO-08 isolated process-death notebook", sortOrder = 0,
                createdAtMillis = 100L, updatedAtMillis = 100L,
            ))
            for ((note, page) in listOf(noteId to pageId, otherNoteId to otherPageId)) {
                db.noteDao().insert(NoteEntity(note, notebookId, "Isolated note", 100L, 100L))
                db.pageDao().insert(PageEntity(
                    page, note, 0L, 595.0, 842.0,
                    """{"type":"blank","color":"#FFFFFF"}""", 0L,
                ))
            }
            val asset = ManagedAssetRegistrar(
                ManagedAssetFileStore(root), db.assetDao(),
            ).register(ByteArrayInputStream(png()), "image/png")
            val command = InsertVerifiedImageCommand(
                noteId = noteId, pageId = pageId, elementId = elementId, assetId = asset.id,
                expectedNoteRevision = 0L, at = null,
                transform = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
                frame = ImageFrame(10.0, 12.0, 80.0, 60.0), opacity = 1.0,
            )
            val repo = RoomDocumentRepository(
                db, clock = { 2_000L }, verifiedImageRoot = root,
            )
            val committed = withTimeout(20_000L) { repo.insertVerifiedImage(command) }
            assertEquals(1L, committed.revision)
            assertEquals(1L, db.noteDao().findById(noteId)!!.revision)
            assertEquals(asset.id, db.assetOwnershipDao().findElementReference(elementId)!!.assetId)
            assertEquals("image", db.elementDao().findById(elementId)!!.kind)
            assertEquals(0L, db.noteDao().findById(otherNoteId)!!.revision)
            assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
            asset
        }
        val original = File(root, asset.relativePath)
        assertTrue(Files.isRegularFile(original.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals(asset.sha256, digest(original.readBytes()))

        // This marker must be durable BEFORE the host observes READY_TO_KILL.
        assertTrue("Failed to durably record acknowledged fixture", prefs.edit()
            .putString("databaseName", databaseName)
            .putString("rootName", rootName)
            .putString("notebookId", notebookId)
            .putString("noteId", noteId)
            .putString("pageId", pageId)
            .putString("otherNoteId", otherNoteId)
            .putString("otherPageId", otherPageId)
            .putString("elementId", elementId)
            .putString("assetId", asset.id)
            .putString("relativePath", asset.relativePath)
            .putString("originalSha", asset.sha256)
            .putInt("preparedPid", Process.myPid())
            .commit())

        Log.i("STO08_PD_ACK", "READY_TO_KILL pid=${Process.myPid()} revision=1")
        // No graceful return/DB close. Host must terminate before this deadline.
        CountDownLatch(1).await(120, TimeUnit.SECONDS)
        fail("Host did not kill acknowledged-image process before timeout")
    }

    @Test
    fun verifyAcknowledgedImageAfterNewProcessStarts() {
        onlyPhase("ack-verify")
        val databaseName = saved("databaseName")
        check(Regex("sto08-pd-ack-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.sqlite").matches(databaseName)) {
            "Unsafe fixture DB name"
        }
        val root = verifiedRoot(saved("rootName"))
        val noteId = saved("noteId")
        val pageId = saved("pageId")
        val otherNoteId = saved("otherNoteId")
        val otherPageId = saved("otherPageId")
        val elementId = saved("elementId")
        val assetId = saved("assetId")
        val relativePath = saved("relativePath")
        val sha = saved("originalSha")
        assertNotEquals("Expected a new Android process", prefs.getInt("preparedPid", -1), Process.myPid())

        val db = AppDatabaseProvider.openIsolated(context, databaseName)
        runBlocking {
            val repo = RoomDocumentRepository(db, verifiedImageRoot = root)
            val recovered = repo.load(noteId)
            assertEquals(1L, recovered.revision)
            assertEquals(1, recovered.pages.size)
            assertEquals(pageId, recovered.pages.single().id)
            val image = db.elementDao().findById(elementId)
            assertNotNull(image)
            assertEquals(pageId, image!!.pageId)
            assertEquals("image", image.kind)
            assertEquals(elementId, JSONObject(image.elementJson).getString("id"))
            assertEquals(assetId, JSONObject(image.elementJson).getJSONObject("payload").getString("assetId"))
            val edges = db.assetOwnershipDao().listElementReferencesByAsset(assetId)
            assertEquals(1, edges.size)
            assertEquals(elementId, edges.single().elementId)
            assertEquals(assetId, db.assetOwnershipDao().findElementReference(elementId)!!.assetId)
            val asset = db.assetDao().findById(assetId)
            assertNotNull(asset)
            assertEquals("finalized", asset!!.state)
            assertEquals("image/png", asset.mimeType)
            assertEquals(relativePath, asset.relativePath)
            assertEquals(sha, asset.sha256)
            assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
            assertEquals(0L, repo.load(otherNoteId).revision)
            assertEquals(otherPageId, repo.load(otherNoteId).pages.single().id)
            assertTrue(db.elementDao().listByPage(otherPageId).isEmpty())
            // OS lease and Room must both be usable after actual termination.
            withTimeout(5_000L) {
                AssetAcquisitionLeaseCoordinator(root).withLease { Unit }
            }
        }
        val original = File(root, relativePath)
        check(original.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            "Asset path escapes isolated fixture"
        }
        assertTrue(Files.isRegularFile(original.toPath(), LinkOption.NOFOLLOW_LINKS))
        val bytes = original.readBytes()
        assertEquals(sha, digest(bytes))
        assertEquals(bytes.size.toLong(), runBlocking { db.assetDao().findById(assetId)!!.byteSize })

        // Cleanup is permitted ONLY after recovery assertions succeed.
        db.close()
        assertTrue("Could not remove isolated DB", context.deleteDatabase(databaseName))
        assertTrue("Could not remove isolated asset root", root.deleteRecursively())
        assertTrue("Could not clear isolated fixture state", prefs.edit().clear().commit())
        Log.i("STO08_PD_ACK", "VERIFY_PASSED revision=1")
    }
}
