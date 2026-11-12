package com.notebook.app.data.assets

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabaseProvider
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 C3-A2-I3-E / Step 153: API-26-compatible, isolated SQLite research.
 *
 * E02: a typed SQLITE_BUSY / SQLiteDatabaseLockedException from a second
 *       SQLiteDatabase handle while an IMMEDIATE writer owns the lock.
 * E06: an EXCLUSIVE rollback-journal holder, a verified lock-conflict probe,
 *       and a CancellationSignal-backed read request issued during the hold.
 *
 * Native execution of BEGIN IMMEDIATE has no CancellationSignal parameter.
 * The worker's request latch is NOT proof that it entered sqlite3_step or waited
 * on the SQLite lock. In E06 cancellation may precede actual query execution;
 * OperationCanceledException and SQLiteDatabaseLockedException are explicitly
 * different diagnostics. A lock exception alone DOES NOT prove cancellation.
 * Neither result establishes interruption of an already-running native query.
 *
 * The bounded watchdog is a fail-closed safety gate, not evidence of lock
 * acquisition. On a watchdog timeout the holder releases its transaction so
 * the contender can unwind. If a native worker still fails to terminate, the
 * test refuses to close/delete that in-use, uniquely named synthetic DB.
 *
 * Research only. No Room production database, managed originals, export, GC,
 * ownership-index promotion, real media bytes, cross-process guarantee, or
 * instrumentation authorization is implied by this authored source.
 */
@RunWith(AndroidJUnit4::class)
class ExportSnapshotResearchSqliteContentionAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var first: SQLiteDatabase
    private lateinit var second: SQLiteDatabase
    private var workerUnresolved = false

    // An E06 lock conflict is evidence of a SQLite conflict, not of cancellation.
    // The probe below already verifies contention separately from the query.
    private enum class E06Outcome {
        SIGNAL_CANCELLED,
        SQLITE_LOCK_CONFLICT,
        UNEXPECTED_QUERY_SUCCESS,
        UNEXPECTED_QUERY_FAILURE,
    }

    private data class E06Finding(
        val outcome: E06Outcome,
        val failureClass: String? = null,
    )

    private fun validName(name: String): Boolean =
        name.matches(Regex("sto08-c3a2-i3e-e2b-[0-9a-f-]{36}\\.sqlite")) &&
            name != AppDatabaseProvider.DATABASE_NAME

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "sto08-c3a2-i3e-e2b-${UUID.randomUUID()}.sqlite"
        check(validName(databaseName))
        val file = context.getDatabasePath(databaseName)
        check(!file.exists()) { "Refuse to open an existing test database" }
        check(!file.absolutePath.contains(".."))
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Missing private test DB directory" }
    }

    @After fun tearDown() {
        // Never close a SQLiteDatabase underneath an unresolved native contender.
        // A stuck synthetic DB is safer than touching unrelated application data.
        check(!workerUnresolved) { "Unresolved SQLite worker; isolated DB deliberately retained" }
        try {
            if (::second.isInitialized) second.close()
        } finally {
            try {
                if (::first.isInitialized) first.close()
            } finally {
                if (::context.isInitialized && ::databaseName.isInitialized) {
                    check(validName(databaseName))
                    assertTrue(
                        "Isolated research DB removal failed",
                        SQLiteDatabase.deleteDatabase(context.getDatabasePath(databaseName)),
                    )
                }
            }
        }
    }

    private fun openPair(wal: Boolean) {
        val file = context.getDatabasePath(databaseName)
        val flags = SQLiteDatabase.OPEN_READWRITE or
            SQLiteDatabase.CREATE_IF_NECESSARY or
            (if (wal) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0)
        first = SQLiteDatabase.openDatabase(file.absolutePath, null, flags)
        first.execSQL("CREATE TABLE sto08_lock_probe (key INTEGER PRIMARY KEY, value TEXT NOT NULL)")
        second = SQLiteDatabase.openDatabase(file.absolutePath, null, flags)

        if (wal) {
            assertTrue("First handle requires WAL", first.isWriteAheadLoggingEnabled)
            assertTrue("Second handle requires WAL", second.isWriteAheadLoggingEnabled)
            assertEquals("wal", scalarString(first, "PRAGMA journal_mode").lowercase())
            assertEquals("wal", scalarString(second, "PRAGMA journal_mode").lowercase())
        } else {
            assertFalse("E06 requires rollback journal", first.isWriteAheadLoggingEnabled)
            assertFalse("E06 requires rollback journal", second.isWriteAheadLoggingEnabled)
            assertTrue("E06 journal mode must not be WAL",
                scalarString(first, "PRAGMA journal_mode").lowercase() != "wal")
            assertTrue("E06 journal mode must not be WAL",
                scalarString(second, "PRAGMA journal_mode").lowercase() != "wal")
        }

        // Do not set connection-local PRAGMA busy_timeout via execSQL: this
        // query-producing PRAGMA is rejected by Android SQLite on API 26.
        // Android SQLiteDatabase may use a pool, so a per-connection
        // setting is not a test-wide timeout. The worker watchdog is only a safety
        // bound; it does not establish a bound on native SQLite lock waiting.
    }

    private fun scalarLong(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun scalarString(db: SQLiteDatabase, sql: String): String =
        db.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    /**
     * Calls [onRequested] after the worker's pre-call marker. That marker is
     * scheduling evidence only; it never certifies actual SQLite lock entry.
     * [releaseOwnerOnTimeout] must end the holder's transaction on this test
     * thread before waiting for the worker to terminate.
     */
    private fun <T> boundedWorker(
        label: String,
        onRequested: () -> Unit = {},
        releaseOwnerOnTimeout: () -> Unit,
        action: () -> T,
    ): T {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sto08-$label").apply { isDaemon = true }
        }
        val requested = CountDownLatch(1)
        val future = executor.submit(Callable<T> {
            requested.countDown()
            action()
        })
        try {
            check(requested.await(5L, TimeUnit.SECONDS)) {
                "$label: worker never reached pre-call marker"
            }
            onRequested()
            try {
                return future.get(10L, TimeUnit.SECONDS)
            } catch (timeout: TimeoutException) {
                // Thread interruption alone cannot cancel every native SQLite call.
                releaseOwnerOnTimeout()
                throw AssertionError("$label: bounded SQLite operation did not complete", timeout)
            } catch (wrapped: ExecutionException) {
                throw AssertionError("$label: unexpected worker failure", wrapped.cause ?: wrapped)
            }
        } finally {
            future.cancel(true)
            executor.shutdownNow()
            if (!executor.awaitTermination(10L, TimeUnit.SECONDS)) {
                workerUnresolved = true
                throw AssertionError("$label: worker did not stop; synthetic DB retained")
            }
        }
    }

    /** Only called by the contender thread; always ends any unexpectedly held tx. */
    private fun trySecondImmediate(): Throwable? {
        var entered = false
        return try {
            second.beginTransactionNonExclusive()
            entered = true
            null
        } catch (locked: SQLiteDatabaseLockedException) {
            locked
        } finally {
            if (entered) second.endTransaction() // rollback, never commit
        }
    }

    @Test fun e02VerifiedImmediateWriterConflictThenForwardProgress() {
        openPair(wal = true)
        assertEquals(0L, scalarLong(first, "SELECT COUNT(*) FROM sto08_lock_probe"))
        assertEquals(0L, scalarLong(second, "SELECT COUNT(*) FROM sto08_lock_probe"))

        first.beginTransactionNonExclusive() // API 11+: IMMEDIATE
        var ownerOpen = true
        val releaseOwner = {
            if (ownerOpen) {
                first.endTransaction()
                ownerOpen = false
            }
        }
        try {
            assertTrue(first.inTransaction())
            first.execSQL("INSERT INTO sto08_lock_probe VALUES (1, 'first')")
            assertEquals(1L, scalarLong(first, "SELECT COUNT(*) FROM sto08_lock_probe"))
            assertEquals(0L, scalarLong(second, "SELECT COUNT(*) FROM sto08_lock_probe"))

            val failure = boundedWorker(
                label = "e02-write-conflict",
                releaseOwnerOnTimeout = releaseOwner,
            ) { trySecondImmediate() }
            assertTrue(
                "A typed SQLiteDatabaseLockedException is required for E02 evidence",
                failure is SQLiteDatabaseLockedException,
            )
            assertEquals(0L, scalarLong(second, "SELECT COUNT(*) FROM sto08_lock_probe"))
            first.setTransactionSuccessful()
        } finally {
            releaseOwner()
        }

        assertEquals(1L, scalarLong(second, "SELECT COUNT(*) FROM sto08_lock_probe"))
        second.beginTransactionNonExclusive()
        try {
            second.execSQL("INSERT INTO sto08_lock_probe VALUES (2, 'second')")
            second.setTransactionSuccessful()
        } finally {
            second.endTransaction()
        }
        assertEquals(2L, scalarLong(first, "SELECT COUNT(*) FROM sto08_lock_probe"))
        assertEquals(2L, scalarLong(second, "SELECT COUNT(*) FROM sto08_lock_probe"))
    }

    @Test fun e06CancellationSignalUnderVerifiedRollbackJournalConflict() {
        openPair(wal = false)
        assertEquals(0L, scalarLong(second, "SELECT COUNT(*) FROM sto08_lock_probe"))

        // Rollback-journal EXCLUSIVE lock blocks competing readers as well as
        // writers. This is intentionally NOT the WAL-mode E02 experiment.
        first.beginTransaction()
        var ownerOpen = true
        val releaseOwner = {
            if (ownerOpen) {
                first.endTransaction()
                ownerOpen = false
            }
        }
        try {
            assertTrue(first.inTransaction())
            first.execSQL("INSERT INTO sto08_lock_probe VALUES (1, 'held')")

            // First prove a real SQLite conflict while the EXCLUSIVE owner lives.
            val conflict = boundedWorker(
                label = "e06-lock-probe",
                releaseOwnerOnTimeout = releaseOwner,
            ) { trySecondImmediate() }
            assertTrue(
                "E06 requires a typed SQLite conflict before cancellation probe",
                conflict is SQLiteDatabaseLockedException,
            )

            val signal = CancellationSignal()
            val queryFinding = boundedWorker(
                label = "e06-cancelled-query",
                onRequested = { signal.cancel() },
                releaseOwnerOnTimeout = releaseOwner,
            ) {
                try {
                    second.rawQuery(
                        "SELECT COUNT(*) FROM sto08_lock_probe", null, signal,
                    ).use { cursor -> check(cursor.moveToFirst()) }
                    E06Finding(E06Outcome.UNEXPECTED_QUERY_SUCCESS)
                } catch (cancelled: OperationCanceledException) {
                    E06Finding(E06Outcome.SIGNAL_CANCELLED, cancelled.javaClass.simpleName)
                } catch (locked: SQLiteDatabaseLockedException) {
                    E06Finding(E06Outcome.SQLITE_LOCK_CONFLICT, locked.javaClass.simpleName)
                } catch (unexpected: RuntimeException) {
                    E06Finding(E06Outcome.UNEXPECTED_QUERY_FAILURE, unexpected.javaClass.simpleName)
                }
            }
            assertTrue("E06 cancellation request must have been signalled", signal.isCanceled)
            Log.i(
                "STO08_I3E_E06",
                "E06_QUERY_OUTCOME=${queryFinding.outcome}; " +
                    "exception=${queryFinding.failureClass ?: "none"}; " +
                    "sqlite_conflict_preverified=true; native_interruption_proven=false",
            )
            when (queryFinding.outcome) {
                E06Outcome.SIGNAL_CANCELLED -> {
                    // A cancellation exception occurred, but the signal may have
                    // arrived before query execution or before any native wait.
                    assertEquals("OperationCanceledException", queryFinding.failureClass)
                }
                E06Outcome.SQLITE_LOCK_CONFLICT -> {
                    // Allowed diagnostic result: confirms conflict, NOT that the
                    // CancellationSignal interrupted a native operation.
                    assertEquals("SQLiteDatabaseLockedException", queryFinding.failureClass)
                }
                E06Outcome.UNEXPECTED_QUERY_SUCCESS,
                E06Outcome.UNEXPECTED_QUERY_FAILURE -> {
                    throw AssertionError(
                        "E06 unexpected query result: ${queryFinding.outcome}; " +
                            "exception=${queryFinding.failureClass ?: "none"}"
                    )
                }
            }
            first.setTransactionSuccessful()
        } finally {
            releaseOwner()
        }

        assertEquals(1L, scalarLong(second, "SELECT COUNT(*) FROM sto08_lock_probe"))
        second.beginTransactionNonExclusive()
        try {
            second.execSQL("INSERT INTO sto08_lock_probe VALUES (2, 'progress')")
            second.setTransactionSuccessful()
        } finally {
            second.endTransaction()
        }
        assertEquals(2L, scalarLong(first, "SELECT COUNT(*) FROM sto08_lock_probe"))
    }
}
