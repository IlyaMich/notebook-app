package com.notebook.app.data.assets

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** I5-A5: isolated Room tests; no actual originals, production database or deletion. */
@RunWith(AndroidJUnit4::class)
class RoomAssetProjectionDiagnosticSnapshotAndroidTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: AppDatabase
    private fun id() = UUID.randomUUID().toString()
    private val blank = """{"type":"blank","color":"#FFFFFF"}"""

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "sto08-i5a5-${UUID.randomUUID()}.sqlite"
        check(name != AppDatabaseProvider.DATABASE_NAME)
        db = AppDatabaseProvider.openIsolated(context, name)
        db.openHelper.writableDatabase
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::name.isInitialized) context.deleteDatabase(name)
    }

    private suspend fun asset(mime: String): String {
        val assetId = id()
        val sha = id().replace("-", "") + id().replace("-", "")
        val ext = if (mime == "application/pdf") "pdf" else "png"
        db.assetDao().insert(AssetEntity(assetId, sha, mime, 16L,
            "assets/${sha.substring(0, 2)}/${sha.substring(2, 4)}/$sha.$ext", "finalized"))
        return assetId
    }

    private suspend fun page(background: String, trash: Boolean = false): String {
        val book = id()
        db.notebookDao().insert(NotebookEntity(id = book, parentFolderId = null,
            name = "isolated", sortOrder = 0L, createdAtMillis = 1L, updatedAtMillis = 1L))
        val note = id()
        db.noteDao().insert(NoteEntity(id = note, notebookId = book, title = "synthetic",
            createdAtMillis = 1L, updatedAtMillis = 1L,
            deletedAtMillis = if (trash) 2L else null))
        val p = id()
        db.pageDao().insert(PageEntity(p, note, 0L, 612.0, 792.0, background, 0L))
        return p
    }

    private fun pdf(assetId: String) =
        """{"type":"pdfPage","assetId":"$assetId","pageIndex":0,"displayBox":"crop"}"""

    private suspend fun completed(): RoomAssetProjectionDiagnosticSnapshot.Result.Completed {
        val outcome = RoomAssetProjectionDiagnosticSnapshot(db).scan()
        assertTrue("Expected a complete diagnostic: $outcome",
            outcome is RoomAssetProjectionDiagnosticSnapshot.Result.Completed)
        return outcome as RoomAssetProjectionDiagnosticSnapshot.Result.Completed
    }

    @Test fun emptyDatabaseRemainsUnverifiedAndNoObservedMismatch() = runBlocking {
        val result = completed()
        assertEquals("UNVERIFIED", result.gateStatus)
        assertTrue(result.canonical.owners.isEmpty())
        assertFalse(result.diagnostic.hasObservedMismatch)
    }

    @Test fun matchingActiveAndTrashOwnersRemainDiagnosticOnly() = runBlocking {
        val assetId = asset("application/pdf")
        val active = page(pdf(assetId))
        val trashed = page(pdf(assetId), trash = true)
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(active, assetId))
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(trashed, assetId))
        val result = completed()
        assertEquals(2, result.canonical.owners.size)
        assertEquals(1, result.canonical.owners.count { it.inTrash })
        assertFalse(result.diagnostic.hasObservedMismatch)
        assertEquals("UNVERIFIED", result.gateStatus)
    }

    @Test fun missingProjectionInTrashIsReported() = runBlocking {
        val assetId = asset("application/pdf")
        val p = page(pdf(assetId), trash = true)
        val result = completed()
        assertTrue(result.diagnostic.findings.any {
            it.kind == AssetProjectionDiagnosticComparator.Kind.MISSING_PROJECTION_EDGE &&
                it.ownerType == "page" && it.ownerId == p
        })
    }

    @Test fun unexpectedProjectionOnBlankPageIsReported() = runBlocking {
        val assetId = asset("application/pdf")
        val p = page(blank)
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(p, assetId))
        val result = completed()
        assertTrue(result.diagnostic.findings.any {
            it.kind == AssetProjectionDiagnosticComparator.Kind.UNEXPECTED_PROJECTION_EDGE &&
                it.ownerId == p
        })
    }

    @Test fun missingMetadataAndMalformedContentRemainUnresolved() = runBlocking {
        val p = page(pdf(id()))
        val elementId = id()
        db.elementDao().insert(ElementEntity(elementId, p, 0L, "image", "{", 0L))
        val result = completed()
        assertTrue(result.canonical.hasUnresolved)
        assertTrue(result.diagnostic.hasObservedMismatch)
    }

    @Test fun unresolvedGateIsNeverPromoted() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE asset_reference_index_state SET state='UNRESOLVED' WHERE id=1")
        val result = completed()
        assertEquals("UNRESOLVED", result.gateStatus)
        assertEquals("UNRESOLVED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun tooManyCanonicalNotesReturnsIncompleteNotPartial() = runBlocking {
        val book = id()
        db.notebookDao().insert(NotebookEntity(id = book, parentFolderId = null,
            name = "bounded", sortOrder = 0L, createdAtMillis = 1L, updatedAtMillis = 1L))
        db.withTransaction {
            repeat(2_001) {
                db.noteDao().insert(NoteEntity(id(), book, "bounded", 1L, 1L))
            }
        }
        val result = RoomAssetProjectionDiagnosticSnapshot(db).scan()
        assertTrue(result is RoomAssetProjectionDiagnosticSnapshot.Result.Incomplete)
    }
}
