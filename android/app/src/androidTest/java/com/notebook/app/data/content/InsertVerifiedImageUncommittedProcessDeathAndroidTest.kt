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
import com.notebook.app.data.content.impl.VerifiedImagePrecommitTestHook
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 C2C-3C-I3: actual process termination during an UNCOMMITTED image insert.
 *
 * HOST ONLY. A separate host driver must invoke `uncommitted-prepare`, observe an
 * exact fresh READY marker, validate the package PID, kill the approved disposable
 * app process, then invoke `uncommitted-verify` in a NEW process without reinstall
 * or data clear. Ordinary Gradle tests skip both phases.
 *
 * The test suspension point is AFTER image element and owner-edge SQL inserts,
 * still INSIDE the writer's own Room transaction and acquisition lease. No outer
 * transaction wrapper inverts the mandatory lease -> Room lock order.
 *
 * On failed/aborted preparation, preserve all UUID-scoped fixtures for diagnosis.
 */
@RunWith(AndroidJUnit4::class)
class InsertVerifiedImageUncommittedProcessDeathAndroidTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prefs
        get() = context.getSharedPreferences("sto08-pd-uncommitted-fixture", Context.MODE_PRIVATE)

    private fun onlyPhase(value: String) {
        assumeTrue(
            "STO-08 uncommitted process-death phases require an explicit ADB host driver",
            InstrumentationRegistry.getArguments().getString("sto08PdPhase") == value,
        )
    }

    private fun id(): String = UUID.randomUUID().toString()

    private fun png(): ByteArray {
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

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun saved(key: String): String =
        prefs.getString(key, null) ?: error("Missing uncommitted fixture key: $key")

    private fun verifiedRoot(name: String): File {
        require(Regex("sto08-pd-uncommitted-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            .matches(name)) { "Unsafe uncommitted fixture root" }
        return File(context.filesDir, name).also { root ->
            check(root.parentFile!!.canonicalFile == context.filesDir.canonicalFile)
            check(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
        }
    }

    @Test
    fun prepareUncommittedImageAndAwaitExternalProcessDeath() {
        onlyPhase("uncommitted-prepare")
        check(!prefs.contains("databaseName")) {
            "Existing uncommitted fixture remains; preserve for diagnosis, do not overwrite"
        }
        val suffix = id()
        val rootName = "sto08-pd-uncommitted-$suffix"
        val databaseName = "$rootName.sqlite"
        val root = File(context.filesDir, rootName)
        assertFalse(root.exists())
        assertTrue("Unable to create isolated image fixture directory", root.mkdir())

        val notebookId = id()
        val noteId = id()
        val pageId = id()
        val otherNoteId = id()
        val otherPageId = id()
        val elementId = id()
        val db = AppDatabaseProvider.openIsolated(context, databaseName)
        runBlocking {
            db.openHelper.writableDatabase
            db.notebookDao().insert(NotebookEntity(
                notebookId, null, "STO-08 uncommitted fixture notebook", sortOrder = 0,
                createdAtMillis = 100L, updatedAtMillis = 100L,
            ))
            for ((note, page) in listOf(noteId to pageId, otherNoteId to otherPageId)) {
                db.noteDao().insert(NoteEntity(note, notebookId, "Isolated note", 100L, 100L))
                db.pageDao().insert(PageEntity(
                    page, note, 0L, 595.0, 842.0,
                    """{"type":"blank","color":"#FFFFFF"}""", 0L,
                ))
            }
            // Asset registration is a separate completed operation, before the
            // test's uncommitted owner transaction and before acquiring its lease.
            val asset = ManagedAssetRegistrar(
                ManagedAssetFileStore(root), db.assetDao(),
            ).register(ByteArrayInputStream(png()), "image/png")
            val original = File(root, asset.relativePath)
            assertTrue(Files.isRegularFile(original.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertEquals(asset.sha256, digest(original.readBytes()))

            // Durable marker location, independent of the Room transaction.
            // This alone never authorizes host kill; the in-transaction hook
            // must additionally validate both rows and emit READY_TO_KILL.
            assertTrue("Could not record isolated fixture", prefs.edit()
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

            val reached = CompletableDeferred<Unit>()
            val neverRelease = CompletableDeferred<Unit>()
            val repo = RoomDocumentRepository(
                db = db,
                clock = { 2_000L },
                verifiedImageRoot = root,
                hook = VerifiedImagePrecommitTestHook {
                    // These queries share the writer's active transaction context:
                    // both writes exist IN the transaction, but neither is committed.
                    val candidateElement = db.elementDao().findById(elementId)
                    assertNotNull("Image element absent at pre-commit boundary", candidateElement)
                    assertEquals("image", candidateElement!!.kind)
                    assertEquals(pageId, candidateElement.pageId)
                    assertEquals(
                        asset.id, db.assetOwnershipDao().findElementReference(elementId)!!.assetId,
                    )
                    assertEquals(0L, db.noteDao().findById(noteId)!!.revision)
                    assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
                    reached.complete(Unit)
                    // KEEP the lease and Room transaction open until the host kills
                    // this entire app process; never release in the prepare phase.
                    neverRelease.await()
                },
            )
            val command = InsertVerifiedImageCommand(
                noteId = noteId, pageId = pageId, elementId = elementId, assetId = asset.id,
                expectedNoteRevision = 0L, at = null,
                transform = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
                frame = ImageFrame(10.0, 12.0, 80.0, 60.0), opacity = 1.0,
            )
            val job = launch(Dispatchers.IO) { repo.insertVerifiedImage(command) }
            withTimeout(20_000L) { reached.await() }
            check(!job.isCompleted) { "Writer transaction unexpectedly completed before kill" }
            Log.i(
                "STO08_PD_UNCOMMITTED",
                "READY_TO_KILL pid=${Process.myPid()} revision=0 element_and_edge_written=true",
            )
            CountDownLatch(1).await(120, TimeUnit.SECONDS)
            fail("Host did not terminate uncommitted-image process before timeout")
        }
    }

    @Test
    fun verifyUncommittedImageRolledBackAfterRestart() {
        onlyPhase("uncommitted-verify")
        val databaseName = saved("databaseName")
        require(Regex("sto08-pd-uncommitted-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.sqlite")
            .matches(databaseName)) { "Unsafe uncommitted fixture DB" }
        val root = verifiedRoot(saved("rootName"))
        val noteId = saved("noteId")
        val pageId = saved("pageId")
        val otherNoteId = saved("otherNoteId")
        val otherPageId = saved("otherPageId")
        val elementId = saved("elementId")
        val assetId = saved("assetId")
        val relativePath = saved("relativePath")
        val sha = saved("originalSha")
        assertNotEquals(
            "New instrumentation process required", prefs.getInt("preparedPid", -1), Process.myPid(),
        )

        val db = AppDatabaseProvider.openIsolated(context, databaseName)
        runBlocking {
            val repo = RoomDocumentRepository(db, verifiedImageRoot = root)
            val recovered = repo.load(noteId)
            assertEquals(0L, recovered.revision)
            assertEquals(1, recovered.pages.size)
            assertEquals(pageId, recovered.pages.single().id)
            assertTrue("Uncommitted owner survived process death", recovered.pages.single().elements.isEmpty())
            assertNull(db.elementDao().findById(elementId))
            assertTrue(db.elementDao().listByPage(pageId).isEmpty())
            assertNull(db.assetOwnershipDao().findElementReference(elementId))
            assertTrue(db.assetOwnershipDao().listElementReferencesByAsset(assetId).isEmpty())
            assertEquals(0L, db.noteDao().findById(noteId)!!.revision)
            val unrelated = repo.load(otherNoteId)
            assertEquals(0L, unrelated.revision)
            assertEquals(otherPageId, unrelated.pages.single().id)
            assertTrue(db.elementDao().listByPage(otherPageId).isEmpty())
            val asset = db.assetDao().findById(assetId)
            assertNotNull("Pre-registered asset must remain finalized", asset)
            assertEquals("finalized", asset!!.state)
            assertEquals(relativePath, asset.relativePath)
            assertEquals(sha, asset.sha256)
            assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
            withTimeout(5_000L) {
                AssetAcquisitionLeaseCoordinator(root).withLease { Unit }
            }
        }
        val original = File(root, relativePath)
        check(original.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            "Source asset path escapes isolated fixture"
        }
        assertTrue(Files.isRegularFile(original.toPath(), LinkOption.NOFOLLOW_LINKS))
        val bytes = original.readBytes()
        assertEquals(sha, digest(bytes))
        assertEquals(bytes.size.toLong(), runBlocking { db.assetDao().findById(assetId)!!.byteSize })

        // Cleanup is allowed only after all reopen, consistency and lease checks.
        db.close()
        assertTrue("Could not delete isolated DB", context.deleteDatabase(databaseName))
        assertTrue("Could not delete isolated asset directory", root.deleteRecursively())
        assertTrue("Could not clear isolated prefs", prefs.edit().clear().commit())
        Log.i("STO08_PD_UNCOMMITTED", "VERIFY_PASSED revision=0")
    }
}
