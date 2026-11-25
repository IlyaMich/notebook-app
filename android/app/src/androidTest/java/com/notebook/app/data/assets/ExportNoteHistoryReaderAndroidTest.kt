package com.notebook.app.data.assets

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.ExportExternalHandoffAttemptEntity
import com.notebook.app.data.local.entity.ExportExternalReadbackObservationEntity
import com.notebook.app.data.local.entity.ExportOperationEntity
import com.notebook.app.data.local.entity.ExportSourceBindingEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** D2J-B tests use isolated Room v7 only. No files, SAF, managed originals or cleanup. */
@RunWith(AndroidJUnit4::class)
class ExportNoteHistoryReaderAndroidTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: AppDatabase
    private lateinit var noteId: String
    private lateinit var otherNoteId: String

    private val limits = ExportPrivateStageLimits(
        maxAssets = 4, maxAssetBytes = 100_000L,
        maxTotalAssetBytes = 400_000L,
        maxMetadataBytes = 100_000L, maxExistingStages = 16,
    )
    private fun id() = UUID.randomUUID().toString()
    private fun reader(max: Int = 20) = ExportNoteHistoryReader(db, limits, max)
    private val shaSource = "a".repeat(64)
    private val shaAssets = "b".repeat(64)
    private val shaArchive = "c".repeat(64)
    private val shaDestination = "d".repeat(64)

    @Before fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "sto08-d2jb-history-" + id() + ".sqlite"
        check(name != AppDatabaseProvider.DATABASE_NAME)
        db = AppDatabaseProvider.openIsolated(context, name)
        db.openHelper.writableDatabase
        val book = id()
        noteId = id()
        otherNoteId = id()
        db.notebookDao().insert(NotebookEntity(
            book, null, "D2JB history test book", sortOrder = 0L,
            createdAtMillis = 1L, updatedAtMillis = 1L,
        ))
        for (note in listOf(noteId, otherNoteId)) {
            db.noteDao().insert(NoteEntity(note, book, "Synthetic", 1L, 1L))
        }
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::name.isInitialized) {
            assertTrue(context.deleteDatabase(name))
        }
    }

    private suspend fun seedOperation(
        note: String = noteId, createdAt: Long = 5L, version: Int = 2,
    ): String {
        val op = id()
        db.exportHoldAdmissionDao().insertOperation(ExportOperationEntity(
            operationId = op, noteId = note, noteRevision = 0L,
            state = "COMMITTED_PIN_SET", createdAtMillis = createdAt,
            updatedAtMillis = createdAt, protocolVersion = version,
        ))
        if (version == 2) {
            db.exportHoldAdmissionDao().insertSourceBinding(ExportSourceBindingEntity(
                operationId = op,
                snapshotSha256 = shaSource,
                assetSetSha256 = shaAssets,
                expectedAssetCount = 0,
                expectedAssetBytes = 0L,
                bindingVersion = 1,
            ))
        }
        return op
    }

    private suspend fun seedAttempt(op: String) {
        db.exportHoldAdmissionDao().insertExternalAttempt(ExportExternalHandoffAttemptEntity(
            operationId = op,
            destinationIdentitySha256 = shaDestination,
            archiveSha256 = shaArchive,
            archiveBytes = 321L,
            snapshotSha256 = shaSource,
            assetSetSha256 = shaAssets,
            attemptedAtMillis = 10L,
            attemptVersion = 1,
        ))
    }

    private suspend fun seedReadback(op: String) {
        db.exportHoldAdmissionDao().insertExternalReadback(
            ExportExternalReadbackObservationEntity(
                operationId = op, observedSha256 = shaArchive,
                observedBytes = 321L, observedAtMillis = 11L, observationVersion = 1,
            ),
        )
    }

    private suspend fun entries(): List<ExportNoteHistoryEntry> {
        val result = reader().inspect(noteId)
        assertTrue("Expected bounded historical observations: $result",
            result is ExportNoteHistoryReport.ObservedUntrusted)
        return (result as ExportNoteHistoryReport.ObservedUntrusted).entries
    }

    @Test fun emptyNoteIsUntrustedEmptyNotAnExportReceipt() = runBlocking {
        assertEquals(ExportNoteHistoryReport.ObservedUntrusted(emptyList()), reader().inspect(noteId))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun malformedNoteIdentityIsFailClosedBeforeRoomInspection() = runBlocking {
        assertEquals(ExportNoteHistoryReport.IncompleteUntrusted, reader().inspect("not-uuid"))
    }

    @Test fun pendingNoAttemptIsNotExternalAbsenceCertificate() = runBlocking {
        val op = seedOperation()
        assertEquals(listOf(ExportNoteHistoryEntry(
            op, 5L, ExportNoteHistoryState.NO_ATTEMPT_RECORDED_UNTRUSTED,
        )), entries())
        assertNull(db.exportHoldAdmissionDao().findExternalAttempt(op))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun providerAttemptWithoutReadbackIsPendingAcrossRoomReopen() = runBlocking {
        val op = seedOperation()
        seedAttempt(op)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        assertEquals(ExportNoteHistoryState.ATTEMPT_PENDING_UNTRUSTED,
            entries().single().state)
        assertNull(db.exportHoldAdmissionDao().findExternalReadback(op))
        assertNotNull(db.exportHoldAdmissionDao().findSourceBinding(op))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun matchingReadbackNeverPromotesExternalDurabilityOrReleasesPins() = runBlocking {
        val op = seedOperation()
        seedAttempt(op)
        seedReadback(op)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        assertEquals(listOf(ExportNoteHistoryEntry(
            op, 5L, ExportNoteHistoryState.READBACK_OBSERVED_UNTRUSTED,
        )), entries())
        assertNotNull(db.exportHoldAdmissionDao().findSourceBinding(op))
        assertEquals("COMMITTED_PIN_SET", db.exportHoldAdmissionDao().findOperation(op)?.state)
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun historyNeverLeaksOtherNotesOperations() = runBlocking {
        val own = seedOperation()
        seedOperation(note = otherNoteId)
        assertEquals(listOf(own), entries().map { it.operationId })
    }

    @Test fun boundedPlusOneDetectsOverflowRatherThanShowingMisleadingPartialHistory() =
        runBlocking {
            repeat(3) { seedOperation(createdAt = it.toLong() + 1L) }
            assertEquals(ExportNoteHistoryReport.IncompleteUntrusted,
                reader(max = 2).inspect(noteId))
            assertEquals(3, db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM export_operations",
            ).use { it.moveToFirst(); it.getInt(0) })
        }

    @Test fun mostRecentFirstAndLegacyRowsStillNeverGrantCompletion() = runBlocking {
        seedOperation(createdAt = 1L, version = 1)
        val recent = seedOperation(createdAt = 7L)
        val rows = entries()
        assertEquals(2, rows.size)
        assertEquals(recent, rows.first().operationId)
        assertEquals(ExportNoteHistoryState.NO_ATTEMPT_RECORDED_UNTRUSTED,
            rows.last().state)
    }
}
