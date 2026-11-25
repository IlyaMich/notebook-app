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

    /**
     * Produce an additional app-private archive copy only after verifying Room
     * v6 source binding and the private portable draft. This is NOT external
     * publication and does not grant hold release, `VERIFIED` or GC authority.
     */
    fun independentPrivateArchiveForApplication(
        context: Context,
        stageLimits: ExportPrivateStageLimits,
    ): ExportIndependentPrivateArchiveCoordinator {
        val app = context.applicationContext
        return independentPrivateArchiveForRoom(
            app.filesDir, AppDatabaseProvider.get(app), stageLimits,
        )
    }

    internal fun independentPrivateArchiveForRoom(
        privateFilesDir: File,
        db: AppDatabase,
        stageLimits: ExportPrivateStageLimits,
    ): ExportIndependentPrivateArchiveCoordinator {
        val coordinator = AssetAcquisitionLeaseCoordinator(privateFilesDir)
        val lease = object : ExportOuterLease {
            override suspend fun <T> withLease(action: suspend () -> T): T =
                coordinator.withLease(action)
        }
        return ExportIndependentPrivateArchiveCoordinator(
            outerLease = lease,
            recovery = ExportBoundPrivateStageRecovery(db, privateFilesDir, stageLimits),
            store = ExportIndependentPrivateArchiveStore(
                privateFilesDir, stageLimits, ExportAndroidDirectorySync::syncOrThrow,
            ),
        )
    }

    /**
     * Explicitly invoked export-provider seam only: caller first launches
     * ACTION_CREATE_DOCUMENT and passes its fresh user-approved result URI.
     * No automatic UI/intent dispatch, completion receipt, release or GC.
     */
    fun safPublicationForApplication(
        context: Context,
        stageLimits: ExportPrivateStageLimits,
    ): ExportSafPublicationCoordinator {
        val app = context.applicationContext
        return safPublicationForRoom(
            app.filesDir, AppDatabaseProvider.get(app), stageLimits,
        )
    }

    internal fun safPublicationForRoom(
        privateFilesDir: File,
        db: AppDatabase,
        stageLimits: ExportPrivateStageLimits,
    ): ExportSafPublicationCoordinator =
        ExportSafPublicationCoordinator(
            privateArchive = independentPrivateArchiveForRoom(
                privateFilesDir, db, stageLimits,
            ),
            bytes = ExportExternalDocumentByteHandoff(privateFilesDir, stageLimits),
            journal = ExportExternalAttemptJournal(db, stageLimits),
        )

    /**
     * D2J-A: opt-in application Room-backed export orchestration composition.
     * This factory does not launch export, open a picker, retain URI grants,
     * recognize provider durability, release a hold, or enable GC.
     * Caller must explicitly review/provide every export/resource limit.
     */
    fun roomBoundHandoffForApplication(
        context: Context,
        snapshotLimits: SnapshotLimits,
        copyLimits: ExportCopyAdmissionLimits,
        stageLimits: ExportPrivateStageLimits,
    ): ExportRoomBoundHandoffCoordinator {
        val app = context.applicationContext
        return roomBoundHandoffForRoom(
            app.filesDir, AppDatabaseProvider.get(app),
            snapshotLimits, copyLimits, stageLimits,
        )
    }

    /** Test-only composition through isolated Room/private-file roots. */
    internal fun roomBoundHandoffForRoom(
        privateFilesDir: File,
        db: AppDatabase,
        snapshotLimits: SnapshotLimits,
        copyLimits: ExportCopyAdmissionLimits,
        stageLimits: ExportPrivateStageLimits,
    ): ExportRoomBoundHandoffCoordinator =
        ExportRoomBoundHandoffCoordinator(
            privateFilesDir, db, snapshotLimits, copyLimits, stageLimits,
        )

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
