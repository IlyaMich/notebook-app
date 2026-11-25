package com.notebook.app.data.assets

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageAssetReferenceEntity
import com.notebook.app.data.local.entity.PageEntity
import java.io.File
import java.security.MessageDigest
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 D3-A: isolated real Room v7, real app-private finalized-file bytes,
 * simulated lease contender only. No original application assets, no GC, no
 * unlink, no export-hold release and no ownership gate update.
 */
@RunWith(AndroidJUnit4::class)
class Sto08D3aReadOnlyOwnershipPreflightAndroidTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private val blank = """{"type":"blank","color":"#FFFFFF"}"""

    private fun id() = UUID.randomUUID().toString()

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "sto08-d3a-" + id() + ".sqlite"
        check(dbName != AppDatabaseProvider.DATABASE_NAME)
        root = File(context.filesDir, "sto08-d3a-isolated-" + id())
        assertFalse(root.exists())
        assertTrue(root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, dbName)
        db.openHelper.writableDatabase
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::dbName.isInitialized) {
            assertTrue(context.deleteDatabase(dbName))
        }
        if (::root.isInitialized) {
            assertTrue(root.name.startsWith("sto08-d3a-isolated-"))
            assertTrue("Isolated D3A test root could not be removed", root.deleteRecursively())
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun pdfBytes(tag: String) = ("%PDF-1.7\n" + tag + "\n%%EOF\n")
        .toByteArray(Charsets.US_ASCII)

    private suspend fun pdfAsset(tag: String, writeOriginal: Boolean = true): Pair<String, File> {
        val data = pdfBytes(tag)
        val digest = sha(data)
        val asset = id()
        val relative = "assets/${digest.take(2)}/${digest.substring(2, 4)}/$digest.pdf"
        val file = File(root, relative)
        if (writeOriginal) {
            assertTrue(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            file.writeBytes(data)
        }
        db.assetDao().insert(AssetEntity(
            asset, digest, "application/pdf", data.size.toLong(), relative, "finalized",
        ))
        return asset to file
    }

    private suspend fun page(assetId: String?, trashed: Boolean = false,
                             rawBackground: String? = null): String {
        val book = id()
        db.notebookDao().insert(NotebookEntity(
            book, null, "Isolated D3A", sortOrder = 0L, createdAtMillis = 1L, updatedAtMillis = 1L,
        ))
        val note = id()
        db.noteDao().insert(NoteEntity(
            note, book, "Disposable owner", 1L, 1L,
            deletedAtMillis = if (trashed) 2L else null,
        ))
        val background = rawBackground ?: if (assetId == null) blank else
            """{"type":"pdfPage","assetId":"$assetId","pageIndex":0,"displayBox":"crop"}"""
        val page = id()
        db.pageDao().insert(PageEntity(page, note, 0L, 600.0, 800.0, background, 0L))
        return page
    }

    private fun preflight(
        maxRows: Int = 128,
        afterChecked: suspend (Int) -> Unit = {},
    ) = Sto08D3aReadOnlyOwnershipPreflight(
        root, db, maxAssetRows = maxRows, afterAssetChecked = afterChecked,
    )

    private suspend fun assertProtected() {
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        assertEquals(0, db.exportHoldAdmissionDao().listRecoveryHoldRows(1).size)
    }

    @Test fun activeAndTrashedPageOwnersSharingOriginalRemainUntrusted() = runBlocking {
        val (asset, original) = pdfAsset("shared")
        val active = page(asset)
        val trash = page(asset, trashed = true)
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(active, asset))
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(trash, asset))

        val observed = preflight().inspect()
        assertEquals(Sto08D3aReadOnlyOwnershipPreflight.Result.ObservedConsistentUntrusted(
            activeOwners = 1, trashOwners = 1, registeredAssets = 1,
            metadataWithoutRecognizedOwner = 0, ownershipGate = "UNVERIFIED",
        ), observed)
        assertArrayEquals(pdfBytes("shared"), original.readBytes())
        assertProtected()
    }

    @Test fun zeroOwnersAndNoAssetsStillNeverProducesDeletionPermit() = runBlocking {
        assertEquals(Sto08D3aReadOnlyOwnershipPreflight.Result.ObservedConsistentUntrusted(
            0, 0, 0, 0, "UNVERIFIED",
        ), preflight().inspect())
        assertProtected()
    }

    @Test fun unownedRegisteredOriginalRemainsUnclassifiedAndIntact() = runBlocking {
        val (_, original) = pdfAsset("unowned but must retain")
        assertEquals(Sto08D3aReadOnlyOwnershipPreflight.Result.ObservedConsistentUntrusted(
            0, 0, 1, 1, "UNVERIFIED",
        ), preflight().inspect())
        assertArrayEquals(pdfBytes("unowned but must retain"), original.readBytes())
        assertProtected()
    }

    @Test fun missingProjectionForTrashedOwnerFailsClosed() = runBlocking {
        val (asset, original) = pdfAsset("missing edge")
        page(asset, trashed = true)
        assertEquals(Sto08D3aReadOnlyOwnershipPreflight.Result.Mismatch(
            Sto08D3aReadOnlyOwnershipPreflight.Reason.CANONICAL_OR_PROJECTION_MISMATCH,
        ), preflight().inspect())
        assertArrayEquals(pdfBytes("missing edge"), original.readBytes())
        assertProtected()
    }

    @Test fun extraProjectionEdgeOnBlankPageFailsClosed() = runBlocking {
        val (asset, original) = pdfAsset("extra edge")
        val p = page(null)
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(p, asset))
        assertEquals(Sto08D3aReadOnlyOwnershipPreflight.Result.Mismatch(
            Sto08D3aReadOnlyOwnershipPreflight.Reason.CANONICAL_OR_PROJECTION_MISMATCH,
        ), preflight().inspect())
        assertTrue(original.isFile)
        assertProtected()
    }

    @Test fun malformedCanonicalBackgroundNeverImpliesNoOwners() = runBlocking {
        page(null, rawBackground = "{")
        assertEquals(Sto08D3aReadOnlyOwnershipPreflight.Result.Mismatch(
            Sto08D3aReadOnlyOwnershipPreflight.Reason.CANONICAL_OR_PROJECTION_MISMATCH,
        ), preflight().inspect())
        assertProtected()
    }

    @Test fun missingFinalizedOriginalFailsClosedWithoutRepairOrDelete() = runBlocking {
        val (asset, original) = pdfAsset("intentionally missing", writeOriginal = false)
        val p = page(asset)
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(p, asset))
        assertEquals(Sto08D3aReadOnlyOwnershipPreflight.Result.Incomplete(
            Sto08D3aReadOnlyOwnershipPreflight.Reason.FILE_INTEGRITY_OR_ACCESS_FAILURE,
        ), preflight().inspect())
        assertFalse(original.exists())
        assertProtected()
    }

    @Test fun assetScanBudgetExceededNeverReturnsConsistency() = runBlocking {
        val (_, first) = pdfAsset("budget first")
        val (_, second) = pdfAsset("budget second")
        assertEquals(Sto08D3aReadOnlyOwnershipPreflight.Result.Incomplete(
            Sto08D3aReadOnlyOwnershipPreflight.Reason.RESOURCE_BUDGET_EXCEEDED,
        ), preflight(maxRows = 1).inspect())
        assertTrue(first.isFile && second.isFile)
        assertEquals(2, db.assetDao().countAll())
        assertProtected()
    }

    @Test fun uncoordinatedByteMutationBetweenPassesIsRejected() = runBlocking {
        val (_, original) = pdfAsset("first pass")
        val result = preflight(afterChecked = {
            // Mutates ONLY the isolated disposable test original, not a real app asset.
            original.writeBytes(pdfBytes("different bytes"))
        }).inspect()
        assertEquals(Sto08D3aReadOnlyOwnershipPreflight.Result.Incomplete(
            Sto08D3aReadOnlyOwnershipPreflight.Reason.CHANGED_DURING_INSPECTION,
        ), result)
        assertProtected()
    }

    @Test fun roomMetadataMutationBetweenSnapshotsIsRejected() = runBlocking {
        val (asset, original) = pdfAsset("metadata mutation")
        val result = preflight(afterChecked = {
            // Fault injection confined to this test's isolated Room database.
            db.openHelper.writableDatabase.execSQL(
                "UPDATE assets SET byte_size = byte_size + 1 WHERE id = ?",
                arrayOf(asset),
            )
        }).inspect()
        assertEquals(Sto08D3aReadOnlyOwnershipPreflight.Result.Incomplete(
            Sto08D3aReadOnlyOwnershipPreflight.Reason.CHANGED_DURING_INSPECTION,
        ), result)
        assertTrue(original.isFile)
        assertProtected()
    }

    @Test fun failedRoomTransactionRollsBackWithoutCreatingCleanupCandidate() = runBlocking {
        val (_, original) = pdfAsset("preexisting")
        val before = db.assetDao().countAll()
        val failed = runCatching {
            db.withTransaction {
                val data = pdfBytes("rollback metadata only")
                val hash = sha(data)
                db.assetDao().insert(AssetEntity(
                    id(), hash, "application/pdf", data.size.toLong(),
                    "assets/${hash.take(2)}/${hash.substring(2, 4)}/$hash.pdf",
                    "finalized",
                ))
                error("rollback this isolated transaction")
            }
        }.exceptionOrNull()
        assertNotNull(failed)
        assertEquals(before, db.assetDao().countAll())
        assertTrue(preflight().inspect() is
            Sto08D3aReadOnlyOwnershipPreflight.Result.ObservedConsistentUntrusted)
        assertTrue(original.isFile)
        assertProtected()
    }

    @Test fun simulatedReclaimerLeaseCannotEnterBeforeInspectionReleases() = runBlocking {
        val (_, original) = pdfAsset("shared lease simulation")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reclaimerEntered = CompletableDeferred<Unit>()
        val observer = async(Dispatchers.Default) {
            preflight(afterChecked = {
                entered.complete(Unit)
                release.await()
            }).inspect()
        }
        try {
            withTimeout(5_000L) { entered.await() }
            val simulatedReclaimer = async(Dispatchers.Default) {
                // An intentionally NONDESTRUCTIVE future-reclaimer contender.
                AssetAcquisitionLeaseCoordinator(root).withLease {
                    reclaimerEntered.complete(Unit)
                    assertTrue(original.isFile)
                }
            }
            try {
                assertNull(withTimeoutOrNull(175L) {
                    reclaimerEntered.await()
                    true
                })
                release.complete(Unit)
                assertTrue(withTimeout(5_000L) { observer.await() } is
                    Sto08D3aReadOnlyOwnershipPreflight.Result.ObservedConsistentUntrusted)
                withTimeout(5_000L) { simulatedReclaimer.await() }
                assertTrue(reclaimerEntered.isCompleted)
                assertArrayEquals(pdfBytes("shared lease simulation"), original.readBytes())
            } finally {
                release.complete(Unit)
                simulatedReclaimer.cancelAndJoin()
            }
        } finally {
            release.complete(Unit)
            observer.cancelAndJoin()
        }
        assertProtected()
    }

    @Test fun cancelledInspectionReleasesLeaseWithOriginalIntact() = runBlocking {
        val (_, original) = pdfAsset("cancel gate")
        val entered = CompletableDeferred<Unit>()
        val worker = async(Dispatchers.Default) {
            preflight(afterChecked = {
                entered.complete(Unit)
                awaitCancellation()
            }).inspect()
        }
        try {
            withTimeout(5_000L) { entered.await() }
        } finally {
            worker.cancelAndJoin()
        }
        withTimeout(5_000L) {
            AssetAcquisitionLeaseCoordinator(root).withLease {
                assertTrue(original.isFile)
            }
        }
        assertArrayEquals(pdfBytes("cancel gate"), original.readBytes())
        assertProtected()
    }
}
