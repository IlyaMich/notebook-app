package com.notebook.app.data.assets

import android.content.Context
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import java.io.File

/** Dependency composition only; a committed hold set is never an export receipt. */
internal object ExportHoldAdmissionComposition {
    /** Not yet invoked by any production export entry point. Never launch export on construction. */
    fun forApplication(context: Context): ExportAtomicHoldAdmission {
        val app = context.applicationContext
        return forRoom(app.filesDir, AppDatabaseProvider.get(app))
    }

    /**
     * Source inspection integration only. Creating this object does not run export;
     * SourceObserved MUST NOT be promoted to a protected destination receipt.
     */
    fun retainedSourceInspectionForApplication(context: Context): ExportRetainedSourceInspectionCoordinator {
        val app = context.applicationContext
        return retainedSourceInspectionForRoom(app.filesDir, AppDatabaseProvider.get(app))
    }

    internal fun retainedSourceInspectionForRoom(
        privateFilesDir: File, db: AppDatabase,
    ): ExportRetainedSourceInspectionCoordinator {
        val coordinator = AssetAcquisitionLeaseCoordinator(privateFilesDir)
        val lease = object : ExportOuterLease {
            override suspend fun <T> withLease(action: suspend () -> T): T =
                coordinator.withLease(action)
        }
        return ExportRetainedSourceInspectionCoordinator(
            outerLease = lease,
            holdAdmission = ExportAtomicHoldAdmission(db, lease),
            inspectSource = ExportLeasedSourceByteInspector(
                privateFilesDir, db.assetDao(),
            )::inspectUnderHeldLease,
        )
    }

    /**
     * Not a user-facing export. Every stage remains UNTRUSTED even after verified
     * byte copies and synced private files. No receipt, release or GC is exposed.
     * Explicit quotas must be reviewed by the calling integration owner.
     */
    fun retainedPrivateCopyForApplication(
        context: Context,
        stageLimits: ExportPrivateStageLimits,
    ): ExportRetainedPrivateCopyCoordinator {
        val app = context.applicationContext
        return retainedPrivateCopyForRoom(app.filesDir, AppDatabaseProvider.get(app), stageLimits)
    }

    internal fun retainedPrivateCopyForRoom(
        privateFilesDir: File,
        db: AppDatabase,
        stageLimits: ExportPrivateStageLimits,
    ): ExportRetainedPrivateCopyCoordinator {
        val coordinator = AssetAcquisitionLeaseCoordinator(privateFilesDir)
        val lease = object : ExportOuterLease {
            override suspend fun <T> withLease(action: suspend () -> T): T =
                coordinator.withLease(action)
        }
        return ExportRetainedPrivateCopyCoordinator(
            outerLease = lease,
            holdAdmission = ExportAtomicHoldAdmission(db, lease),
            borrower = ExportLeasedSourceByteInspector(privateFilesDir, db.assetDao()),
            stages = ExportRetainedPrivateStageStore(
                privateFilesDir, stageLimits, ExportAndroidDirectorySync::syncOrThrow,
            ),
        )
    }

    /** Allows isolated Room verification without accessing the application's library.sqlite. */
    internal fun forRoom(privateFilesDir: File, db: AppDatabase): ExportAtomicHoldAdmission {
        val coordinator = AssetAcquisitionLeaseCoordinator(privateFilesDir)
        val outerLease = object : ExportOuterLease {
            override suspend fun <T> withLease(action: suspend () -> T): T =
                coordinator.withLease(action)
        }
        return ExportAtomicHoldAdmission(db, outerLease)
    }
}
