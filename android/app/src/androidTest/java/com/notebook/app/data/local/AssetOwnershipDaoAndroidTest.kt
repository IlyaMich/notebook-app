package com.notebook.app.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.entity.ElementAssetReferenceEntity
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageAssetReferenceEntity
import com.notebook.app.data.local.entity.PageEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 C2B-1: tests of low-level Room v4 ownership primitives only.
 *
 * Each test uses a unique isolated on-disk database. This suite never opens
 * AppDatabaseProvider.DATABASE_NAME, never touches user files and never
 * registers actual original bytes. Synthetic metadata is not evidence of
 * finalized/verified bytes; production media writers remain disabled.
 */
@RunWith(AndroidJUnit4::class)
class AssetOwnershipDaoAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "sto08-c2b1-${UUID.randomUUID()}.sqlite"
        check(databaseName != AppDatabaseProvider.DATABASE_NAME)
        context.deleteDatabase(databaseName)
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
    }

    private data class Owner(val noteId: String, val pageId: String, val elementId: String)

    private fun id(): String = UUID.randomUUID().toString()

    private fun pdfJson(assetId: String): String =
        """{"type":"pdfPage","assetId":"$assetId","pageIndex":0,"displayBox":"crop"}"""

    private fun imageJson(elementId: String, assetId: String): String =
        """{"id":"$elementId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""

    private suspend fun addAsset(mime: String): String {
        val assetId = id()
        val sha = id().replace("-", "") + id().replace("-", "")
        val extension = if (mime == "application/pdf") "pdf" else "png"
        db.assetDao().insert(
            AssetEntity(
                id = assetId,
                sha256 = sha,
                mimeType = mime,
                byteSize = 10L,
                relativePath = "assets/${sha.substring(0, 2)}/${sha.substring(2, 4)}/$sha.$extension",
                state = "finalized",
            )
        )
        return assetId
    }

    private suspend fun addOwner(pdfAssetId: String, imageAssetId: String): Owner {
        val notebookId = id()
        db.notebookDao().insert(
            NotebookEntity(
                id = notebookId, parentFolderId = null, name = "C2B-1 synthetic",
                sortOrder = 0L, createdAtMillis = 1L, updatedAtMillis = 1L,
            )
        )
        val noteId = id()
        db.noteDao().insert(NoteEntity(noteId, notebookId, "Synthetic", 1L, 1L))
        val pageId = id()
        db.pageDao().insert(PageEntity(pageId, noteId, 0L, 612.0, 792.0, pdfJson(pdfAssetId), 0L))
        val elementId = id()
        db.elementDao().insert(
            ElementEntity(elementId, pageId, 0L, "image", imageJson(elementId, imageAssetId), 0L)
        )
        return Owner(noteId, pageId, elementId)
    }

    private suspend fun attach(owner: Owner, pdfAssetId: String, imageAssetId: String) {
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(owner.pageId, pdfAssetId))
        db.assetOwnershipDao().insertElementReference(
            ElementAssetReferenceEntity(owner.elementId, imageAssetId)
        )
    }

    private suspend fun mustReject(label: String, block: suspend () -> Unit) {
        val failure = try {
            block()
            null
        } catch (error: Exception) {
            error
        }
        assertNotNull(label, failure)
    }

    @Test fun freshGateIsConservativeAndCanReportUnresolved() = runBlocking {
        val dao = db.assetOwnershipDao()
        assertEquals("UNVERIFIED", dao.readFailClosedGateStatus())
        val raw = db.openHelper.writableDatabase
        raw.execSQL("UPDATE asset_reference_index_state SET state='UNRESOLVED' WHERE id=1")
        assertEquals("UNRESOLVED", dao.readFailClosedGateStatus())
        mustReject("Direct VERIFIED promotion must fail") {
            raw.execSQL("UPDATE asset_reference_index_state SET state='VERIFIED' WHERE id=1")
        }
        assertEquals("UNRESOLVED", dao.readFailClosedGateStatus())
        raw.execSQL("UPDATE asset_reference_index_state SET state='UNVERIFIED' WHERE id=1")
        assertEquals("UNVERIFIED", dao.readFailClosedGateStatus())
    }

    @Test fun independentPageAndElementOwnersCanShareAssets() = runBlocking {
        val pdf = addAsset("application/pdf")
        val image = addAsset("image/png")
        val first = addOwner(pdf, image)
        val second = addOwner(pdf, image)
        db.withTransaction {
            attach(first, pdf, image)
            attach(second, pdf, image)
        }
        val dao = db.assetOwnershipDao()
        assertEquals(PageAssetReferenceEntity(first.pageId, pdf), dao.findPageReference(first.pageId))
        assertEquals(ElementAssetReferenceEntity(second.elementId, image), dao.findElementReference(second.elementId))
        assertEquals(
            listOf(first.pageId, second.pageId).sorted(),
            dao.listPageReferencesByAsset(pdf).map { it.pageId },
        )
        assertEquals(
            listOf(first.elementId, second.elementId).sorted(),
            dao.listElementReferencesByAsset(image).map { it.elementId },
        )
        assertTrue(dao.listPageReferencesByAsset(image).isEmpty())
        assertTrue(dao.listElementReferencesByAsset(pdf).isEmpty())
        assertEquals("UNVERIFIED", dao.readFailClosedGateStatus())
    }

    @Test fun duplicateAndMissingForeignKeysRejectWithoutOverwritingEdges() = runBlocking {
        val pdf = addAsset("application/pdf")
        val image = addAsset("image/png")
        val owner = addOwner(pdf, image)
        attach(owner, pdf, image)
        val dao = db.assetOwnershipDao()
        mustReject("Duplicate page owner") {
            dao.insertPageReference(PageAssetReferenceEntity(owner.pageId, image))
        }
        mustReject("Duplicate element owner") {
            dao.insertElementReference(ElementAssetReferenceEntity(owner.elementId, pdf))
        }
        mustReject("Missing page owner") {
            dao.insertPageReference(PageAssetReferenceEntity(id(), pdf))
        }
        mustReject("Missing element owner") {
            dao.insertElementReference(ElementAssetReferenceEntity(id(), image))
        }
        mustReject("Missing asset on page") {
            dao.insertPageReference(PageAssetReferenceEntity(addOwner(pdf, image).pageId, id()))
        }
        mustReject("Missing asset on element") {
            dao.insertElementReference(ElementAssetReferenceEntity(addOwner(pdf, image).elementId, id()))
        }
        assertEquals(PageAssetReferenceEntity(owner.pageId, pdf), dao.findPageReference(owner.pageId))
        assertEquals(ElementAssetReferenceEntity(owner.elementId, image), dao.findElementReference(owner.elementId))
        assertEquals("UNVERIFIED", dao.readFailClosedGateStatus())
    }

    @Test fun compareAndSetUpdatesAndRemovalsRequireExpectedAsset() = runBlocking {
        val pdfBefore = addAsset("application/pdf")
        val pdfAfter = addAsset("application/pdf")
        val imageBefore = addAsset("image/png")
        val imageAfter = addAsset("image/png")
        val owner = addOwner(pdfBefore, imageBefore)
        attach(owner, pdfBefore, imageBefore)
        val dao = db.assetOwnershipDao()

        assertEquals(0, dao.changePageAssetIfMatches(owner.pageId, pdfAfter, pdfBefore))
        assertEquals(0, dao.changeElementAssetIfMatches(owner.elementId, imageAfter, imageBefore))
        assertEquals(1, dao.changePageAssetIfMatches(owner.pageId, pdfBefore, pdfAfter))
        assertEquals(1, dao.changeElementAssetIfMatches(owner.elementId, imageBefore, imageAfter))
        assertEquals(0, dao.changePageAssetIfMatches(owner.pageId, pdfBefore, pdfAfter))
        assertEquals(0, dao.changeElementAssetIfMatches(owner.elementId, imageBefore, imageAfter))
        mustReject("Unknown target asset must be rejected") {
            dao.changePageAssetIfMatches(owner.pageId, pdfAfter, id())
        }
        assertEquals(pdfAfter, dao.findPageReference(owner.pageId)?.assetId)
        assertEquals(imageAfter, dao.findElementReference(owner.elementId)?.assetId)

        assertEquals(0, dao.removePageReferenceIfMatches(owner.pageId, pdfBefore))
        assertEquals(0, dao.removeElementReferenceIfMatches(owner.elementId, imageBefore))
        assertEquals(1, dao.removePageReferenceIfMatches(owner.pageId, pdfAfter))
        assertEquals(1, dao.removeElementReferenceIfMatches(owner.elementId, imageAfter))
        assertNull(dao.findPageReference(owner.pageId))
        assertNull(dao.findElementReference(owner.elementId))
        assertEquals(4, db.assetDao().countAll()) // No asset metadata or file reclamation.
        assertEquals("UNVERIFIED", dao.readFailClosedGateStatus())
    }

    @Test fun restrictForeignKeysProtectExistingOwnersAndSharedAssets() = runBlocking {
        val pdf = addAsset("application/pdf")
        val image = addAsset("image/png")
        val owner = addOwner(pdf, image)
        attach(owner, pdf, image)
        val raw = db.openHelper.writableDatabase
        mustReject("Referenced asset must not be deleted") {
            raw.execSQL("DELETE FROM assets WHERE id=?", arrayOf(pdf))
        }
        mustReject("Referenced image metadata must not be deleted") {
            raw.execSQL("DELETE FROM assets WHERE id=?", arrayOf(image))
        }
        mustReject("Referenced page must not be deleted") {
            raw.execSQL("DELETE FROM pages WHERE id=?", arrayOf(owner.pageId))
        }
        mustReject("Referenced element must not be deleted") {
            raw.execSQL("DELETE FROM elements WHERE id=?", arrayOf(owner.elementId))
        }
        assertNotNull(db.pageDao().findById(owner.pageId))
        assertNotNull(db.elementDao().findById(owner.elementId))
        assertEquals(2, db.assetDao().countAll())
        raw.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test fun injectedFailureRollsBackCanonicalJsonEdgesAndRevisions() = runBlocking {
        val pdfBefore = addAsset("application/pdf")
        val pdfAfter = addAsset("application/pdf")
        val imageBefore = addAsset("image/png")
        val imageAfter = addAsset("image/png")
        val owner = addOwner(pdfBefore, imageBefore)
        attach(owner, pdfBefore, imageBefore)
        val initialNote = db.noteDao().findById(owner.noteId)!!
        val initialPage = db.pageDao().findById(owner.pageId)!!
        val initialElement = db.elementDao().findById(owner.elementId)!!
        val failure = runCatching {
            db.withTransaction {
                assertEquals(1, db.noteDao().update(initialNote.copy(revision = 9L)))
                assertEquals(1, db.pageDao().update(initialPage.copy(
                    backgroundJson = pdfJson(pdfAfter), revision = 9L,
                )))
                assertEquals(1, db.elementDao().update(initialElement.copy(
                    elementJson = imageJson(owner.elementId, imageAfter), revision = 9L,
                )))
                assertEquals(1, db.assetOwnershipDao().changePageAssetIfMatches(
                    owner.pageId, pdfBefore, pdfAfter,
                ))
                assertEquals(1, db.assetOwnershipDao().changeElementAssetIfMatches(
                    owner.elementId, imageBefore, imageAfter,
                ))
                error("STO-08 injected rollback")
            }
        }.exceptionOrNull()
        assertNotNull("Expected injected failure", failure)
        assertEquals(initialNote, db.noteDao().findById(owner.noteId))
        assertEquals(initialPage, db.pageDao().findById(owner.pageId))
        assertEquals(initialElement, db.elementDao().findById(owner.elementId))
        assertEquals(pdfBefore, db.assetOwnershipDao().findPageReference(owner.pageId)?.assetId)
        assertEquals(imageBefore, db.assetOwnershipDao().findElementReference(owner.elementId)?.assetId)
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun syntheticAtomicCommitPersistsTogetherWithoutEnablingMediaWrites() = runBlocking {
        val pdfBefore = addAsset("application/pdf")
        val pdfAfter = addAsset("application/pdf")
        val imageBefore = addAsset("image/png")
        val imageAfter = addAsset("image/png")
        val owner = addOwner(pdfBefore, imageBefore)
        attach(owner, pdfBefore, imageBefore)
        db.withTransaction {
            val oldNote = db.noteDao().findById(owner.noteId)!!
            val oldPage = db.pageDao().findById(owner.pageId)!!
            val oldElement = db.elementDao().findById(owner.elementId)!!
            assertEquals(1, db.noteDao().update(oldNote.copy(revision = 3L)))
            assertEquals(1, db.pageDao().update(oldPage.copy(
                backgroundJson = pdfJson(pdfAfter), revision = 3L,
            )))
            assertEquals(1, db.elementDao().update(oldElement.copy(
                elementJson = imageJson(owner.elementId, imageAfter), revision = 3L,
            )))
            check(db.assetOwnershipDao().changePageAssetIfMatches(owner.pageId, pdfBefore, pdfAfter) == 1)
            check(db.assetOwnershipDao().changeElementAssetIfMatches(owner.elementId, imageBefore, imageAfter) == 1)
        }
        assertEquals(3L, db.noteDao().findById(owner.noteId)?.revision)
        assertEquals(3L, db.pageDao().findById(owner.pageId)?.revision)
        assertEquals(3L, db.elementDao().findById(owner.elementId)?.revision)
        assertEquals(pdfJson(pdfAfter), db.pageDao().findById(owner.pageId)?.backgroundJson)
        assertEquals(imageJson(owner.elementId, imageAfter), db.elementDao().findById(owner.elementId)?.elementJson)
        assertEquals(pdfAfter, db.assetOwnershipDao().findPageReference(owner.pageId)?.assetId)
        assertEquals(imageAfter, db.assetOwnershipDao().findElementReference(owner.elementId)?.assetId)
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun gateReadFailsClosedForMissingInvalidAndExtraRows() = runBlocking {
        val dao = db.assetOwnershipDao()
        val raw = db.openHelper.writableDatabase
        assertEquals("UNVERIFIED", dao.readFailClosedGateStatus())
        // Deliberate corruption ONLY within this disposable, isolated test DB.
        raw.execSQL("DROP TRIGGER trg_asset_ref_gate_update")
        raw.execSQL("UPDATE asset_reference_index_state SET state='BOGUS' WHERE id=1")
        assertEquals("UNSAFE", dao.readFailClosedGateStatus())
        raw.execSQL("DROP TRIGGER trg_asset_ref_gate_delete")
        raw.execSQL("DELETE FROM asset_reference_index_state WHERE id=1")
        assertEquals("UNSAFE", dao.readFailClosedGateStatus())
        raw.execSQL("DROP TRIGGER trg_asset_ref_gate_insert")
        raw.execSQL("INSERT INTO asset_reference_index_state(id,state) VALUES (1,'UNVERIFIED')")
        raw.execSQL("INSERT INTO asset_reference_index_state(id,state) VALUES (2,'UNVERIFIED')")
        assertEquals("UNSAFE", dao.readFailClosedGateStatus())
    }

    @Test fun indexAndGateSurviveIsolatedDatabaseReopen() = runBlocking {
        val pdf = addAsset("application/pdf")
        val image = addAsset("image/png")
        val owner = addOwner(pdf, image)
        attach(owner, pdf, image)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        assertEquals(PageAssetReferenceEntity(owner.pageId, pdf), db.assetOwnershipDao().findPageReference(owner.pageId))
        assertEquals(ElementAssetReferenceEntity(owner.elementId, image), db.assetOwnershipDao().findElementReference(owner.elementId))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }
}
