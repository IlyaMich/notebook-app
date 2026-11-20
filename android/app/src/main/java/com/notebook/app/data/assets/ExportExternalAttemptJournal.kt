package com.notebook.app.data.assets

import androidx.room.withTransaction
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.entity.ExportExternalHandoffAttemptEntity
import com.notebook.app.data.local.entity.ExportExternalReadbackObservationEntity
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * D2G: an APP-LOCAL, append-only crash diagnostic. The attempt commits BEFORE
 * any external provider I/O. An interrupted attempt is terminally ambiguous;
 * a second attempt for the same operation is refused even after process death.
 *
 * A separately inserted matching readback is still UNTRUSTED: SQLite durability
 * does not establish remote provider power-loss durability, document visibility,
 * automatic hold release or GC eligibility.
 *
 * No startup hook or write-on-recovery, no archive/URI deletion, no expiration.
 */
internal class ExportExternalAttemptJournal(
    private val db: AppDatabase,
    private val limits: ExportPrivateStageLimits,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    internal sealed interface Admission {
        data class Recorded(val attempt: ExportExternalHandoffAttemptEntity) : Admission
        data class Incomplete(val reason: Reason) : Admission
    }

    internal enum class Reason {
        INVALID_REQUEST, LEGACY_OR_MISSING_BINDING, PIN_SET_INCOMPLETE,
        ALREADY_ATTEMPTED, NO_PRIOR_ATTEMPT, READBACK_MISMATCH,
        INVALID_PERSISTED_STATE,
    }

    internal sealed interface Recovery {
        /** No known attempt. NOT an assertion that no external bytes exist. */
        data object NoAttemptObservedUntrusted : Recovery
        /** Destination might have a partial, complete or inaccessible document. */
        data class AttemptPendingUntrusted(
            val attempt: ExportExternalHandoffAttemptEntity,
        ) : Recovery
        /** Matching bytes were once read back; still not a durable receipt. */
        data class ReadbackObservedUntrusted(
            val attempt: ExportExternalHandoffAttemptEntity,
            val observation: ExportExternalReadbackObservationEntity,
        ) : Recovery
        data class Incomplete(val reason: Reason) : Recovery
    }

    suspend fun beginOnce(
        archive: ExportIndependentPrivateArchiveStore.CopyObservedUntrusted,
        destinationIdentitySha256: String,
    ): Admission {
        currentCoroutineContext().ensureActive()
        if (!validIdentity(archive.operationId) ||
            archive.privateFileName != archive.operationId + ".notebook" ||
            !validDigest(archive.sha256) ||
            !validDigest(destinationIdentitySha256) ||
            archive.bytes <= 0L || archive.bytes > maxArchiveBytes()
        ) return Admission.Incomplete(Reason.INVALID_REQUEST)
        return db.withTransaction {
            currentCoroutineContext().ensureActive()
            val dao = db.exportHoldAdmissionDao()
            if (dao.findExternalAttempt(archive.operationId) != null) {
                return@withTransaction Admission.Incomplete(Reason.ALREADY_ATTEMPTED)
            }
            val operation = dao.findOperation(archive.operationId)
                ?: return@withTransaction Admission.Incomplete(Reason.LEGACY_OR_MISSING_BINDING)
            val binding = dao.findSourceBinding(archive.operationId)
                ?: return@withTransaction Admission.Incomplete(Reason.LEGACY_OR_MISSING_BINDING)
            if (operation.state != "COMMITTED_PIN_SET" || operation.protocolVersion != 2 ||
                binding.bindingVersion != ExportSourceBindingDigests.BINDING_VERSION ||
                !validDigest(binding.snapshotSha256) || !validDigest(binding.assetSetSha256)
            ) return@withTransaction Admission.Incomplete(Reason.LEGACY_OR_MISSING_BINDING)
            val holds = dao.listHoldsBounded(archive.operationId, limits.maxAssets + 1)
            if (holds.size > limits.maxAssets ||
                holds.size != binding.expectedAssetCount ||
                holds.map { it.assetId }.distinct().size != holds.size ||
                binding.expectedAssetBytes < 0L ||
                binding.expectedAssetBytes > limits.maxTotalAssetBytes
            ) return@withTransaction Admission.Incomplete(Reason.PIN_SET_INCOMPLETE)
            val now = clockMillis()
            if (now < 0L) return@withTransaction Admission.Incomplete(Reason.INVALID_REQUEST)
            val row = ExportExternalHandoffAttemptEntity(
                operationId = archive.operationId,
                destinationIdentitySha256 = destinationIdentitySha256,
                archiveSha256 = archive.sha256,
                archiveBytes = archive.bytes,
                snapshotSha256 = binding.snapshotSha256,
                assetSetSha256 = binding.assetSetSha256,
                attemptedAtMillis = now,
                attemptVersion = 1,
            )
            currentCoroutineContext().ensureActive()
            dao.insertExternalAttempt(row)
            Admission.Recorded(row)
        }
    }

    suspend fun recordMatchingReread(
        observed: ExportExternalDocumentByteHandoff.RereadObservedUntrusted,
    ): ExportExternalReadbackObservationEntity = db.withTransaction {
        currentCoroutineContext().ensureActive()
        val dao = db.exportHoldAdmissionDao()
        val attempt = dao.findExternalAttempt(observed.operationId)
            ?: throw IOException("Provider readback had no durable attempt record")
        if (dao.findExternalReadback(observed.operationId) != null) {
            throw IOException("External provider readback already recorded; never overwrite")
        }
        val binding = dao.findSourceBinding(observed.operationId)
            ?: throw IOException("External provider readback missing source binding")
        val holds = dao.listHoldsBounded(observed.operationId, limits.maxAssets + 1)
        if (binding.snapshotSha256 != attempt.snapshotSha256 ||
            binding.assetSetSha256 != attempt.assetSetSha256 ||
            holds.size != binding.expectedAssetCount ||
            holds.size > limits.maxAssets ||
            observed.sha256 != attempt.archiveSha256 ||
            observed.byteCount != attempt.archiveBytes
        ) throw IOException("External byte observation differs from committed attempt and holds")
        val now = clockMillis()
        if (now < attempt.attemptedAtMillis) {
            throw IOException("External observation time precedes attempt")
        }
        val observation = ExportExternalReadbackObservationEntity(
            operationId = observed.operationId,
            observedSha256 = observed.sha256,
            observedBytes = observed.byteCount,
            observedAtMillis = now,
            observationVersion = 1,
        )
        currentCoroutineContext().ensureActive()
        dao.insertExternalReadback(observation)
        observation
    }

    /** One read-only Room transaction, no stage or external provider access. */
    suspend fun inspectReadOnly(operationId: String): Recovery {
        if (!validIdentity(operationId)) return Recovery.Incomplete(Reason.INVALID_REQUEST)
        return try {
            db.withTransaction {
                currentCoroutineContext().ensureActive()
                val dao = db.exportHoldAdmissionDao()
                val attempt = dao.findExternalAttempt(operationId)
                    ?: return@withTransaction Recovery.NoAttemptObservedUntrusted
                val operation = dao.findOperation(operationId)
                val binding = dao.findSourceBinding(operationId)
                val holds = dao.listHoldsBounded(operationId, limits.maxAssets + 1)
                if (operation == null || operation.protocolVersion != 2 ||
                    operation.state != "COMMITTED_PIN_SET" ||
                    binding == null || binding.bindingVersion != 1 ||
                    binding.snapshotSha256 != attempt.snapshotSha256 ||
                    binding.assetSetSha256 != attempt.assetSetSha256 ||
                    holds.size > limits.maxAssets ||
                    holds.size != binding.expectedAssetCount ||
                    attempt.attemptVersion != 1 ||
                    attempt.attemptedAtMillis < 0L ||
                    attempt.archiveBytes <= 0L || attempt.archiveBytes > maxArchiveBytes() ||
                    !validDigest(attempt.destinationIdentitySha256) ||
                    !validDigest(attempt.archiveSha256)
                ) return@withTransaction Recovery.Incomplete(Reason.INVALID_PERSISTED_STATE)
                val observation = dao.findExternalReadback(operationId)
                    ?: return@withTransaction Recovery.AttemptPendingUntrusted(attempt)
                if (observation.observationVersion != 1 ||
                    observation.observedAtMillis < attempt.attemptedAtMillis ||
                    observation.observedSha256 != attempt.archiveSha256 ||
                    observation.observedBytes != attempt.archiveBytes
                ) return@withTransaction Recovery.Incomplete(Reason.INVALID_PERSISTED_STATE)
                Recovery.ReadbackObservedUntrusted(attempt, observation)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            Recovery.Incomplete(Reason.INVALID_PERSISTED_STATE)
        }
    }

    private fun maxArchiveBytes(): Long =
        limits.maxTotalAssetBytes + 2L * limits.maxMetadataBytes +
            1024L * (limits.maxAssets + 128L)

    private fun validDigest(value: String): Boolean = SHA.matches(value)

    private fun validIdentity(value: String): Boolean =
        ExportSourceBindingDigests.canonicalUuid(value)

    private companion object {
        val SHA = Regex("[0-9a-f]{64}")
    }
}
