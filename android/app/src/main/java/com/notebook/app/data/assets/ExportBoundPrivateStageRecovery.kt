package com.notebook.app.data.assets

import androidx.room.withTransaction
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.entity.ExportOperationEntity
import com.notebook.app.data.local.entity.ExportSourceBindingEntity
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Read-only restart audit: derive stage expectations from immutable Room v6 source
 * binding, NOT a caller-supplied stage observation. v5 operations stay unbound.
 *
 * Even BoundStageUntrusted is NOT an independently published export receipt,
 * hold-release permit, global owner certification, or GC authority. Never
 * mutates Room, staging files, managed originals, or the ownership index.
 */
internal class ExportBoundPrivateStageRecovery(
    private val db: AppDatabase,
    privateRoot: File,
    private val limits: ExportPrivateStageLimits,
) {
    internal enum class Stop {
        INVALID_ID, OPERATION_MISSING, LEGACY_UNBOUND, INVALID_BINDING,
        PINS_INCOMPLETE, STAGE_MISSING, STAGE_UNSAFE, SNAPSHOT_MISMATCH,
        ASSET_SET_MISMATCH, STAGE_INTEGRITY_FAILED, ARCHIVE_INTEGRITY_FAILED,
        DATABASE_CHANGED_OR_UNREADABLE,
    }

    internal sealed interface Result {
        data class BoundStageUntrusted(
            val observation: ExportUntrustedPrivateStageObservation,
            val draft: ExportPrivatePortableDraftWriter.DraftUntrusted?,
        ) : Result
        data class Incomplete(val reason: Stop) : Result
    }

    private data class Pinned(
        val operation: ExportOperationEntity,
        val binding: ExportSourceBindingEntity,
        val holdIds: List<String>,
    )

    private val root = privateRoot.toPath().toAbsolutePath().normalize()

    suspend fun inspectReadOnly(operationId: String): Result {
        if (!ExportSourceBindingDigests.canonicalUuid(operationId)) {
            return Result.Incomplete(Stop.INVALID_ID)
        }
        return try {
            val committed = readPinned(operationId)
                ?: return Result.Incomplete(Stop.LEGACY_UNBOUND)
            if (committed.operation.state != "COMMITTED_PIN_SET" ||
                committed.operation.protocolVersion != 2 ||
                committed.operation.noteRevision < 0 ||
                !ExportSourceBindingDigests.canonicalUuid(committed.operation.noteId) ||
                committed.binding.bindingVersion != ExportSourceBindingDigests.BINDING_VERSION ||
                !SHA.matches(committed.binding.snapshotSha256) ||
                !SHA.matches(committed.binding.assetSetSha256) ||
                committed.binding.expectedAssetCount !in 0..limits.maxAssets ||
                committed.binding.expectedAssetBytes !in 0L..limits.maxTotalAssetBytes ||
                committed.holdIds.size != committed.binding.expectedAssetCount ||
                committed.holdIds.size != committed.holdIds.distinct().size
            ) return Result.Incomplete(Stop.INVALID_BINDING)

            // Source bytes MUST match the digest stored in the same transaction as
            // the operation and all pins. The stage alone can never invent it.
            val stageDir = root.resolve("pending-export-copies").resolve(operationId)
            val captured = readBoundedNoFollow(
                stageDir.resolve("snapshot.json"),
                minOf(limits.maxMetadataBytes, ExportSourceBindingDigests.MAX_CAPTURED_SNAPSHOT_BYTES),
            ) ?: return Result.Incomplete(Stop.STAGE_MISSING)
            if (ExportSourceBindingDigests.sha256(captured) != committed.binding.snapshotSha256) {
                return Result.Incomplete(Stop.SNAPSHOT_MISMATCH)
            }
            val snapshot = Json.parseToJsonElement(
                captured.toString(Charsets.UTF_8),
            ).jsonObject
            if (snapshot.str("format") != "internal-untrusted-captured-note-v1" ||
                snapshot.str("operationId") != operationId
            ) return Result.Incomplete(Stop.SNAPSHOT_MISMATCH)
            val note = snapshot.getValue("note").jsonObject
            if (note.str("id") != committed.operation.noteId ||
                note.getValue("revision").jsonPrimitive.long != committed.operation.noteRevision
            ) return Result.Incomplete(Stop.SNAPSHOT_MISMATCH)

            val source = snapshot.getValue("assets").jsonArray.map { it.jsonObject }
            if (source.size != committed.binding.expectedAssetCount) {
                return Result.Incomplete(Stop.ASSET_SET_MISMATCH)
            }
            val assets = source.map {
                SnapshotAssetMetadata(
                    id = it.str("id"),
                    sha256 = it.str("sha256"),
                    mimeType = it.str("mimeType"),
                    encodedBytes = it.getValue("encodedBytes").jsonPrimitive.long,
                    relativePath = it.str("registeredRelativePath"),
                    state = it.str("state"),
                )
            }
            val ids = assets.map { it.id }.sorted()
            if (ids != committed.holdIds ||
                ExportSourceBindingDigests.assetSetDigest(assets) != committed.binding.assetSetSha256 ||
                assets.sumOf { it.encodedBytes } != committed.binding.expectedAssetBytes
            ) return Result.Incomplete(Stop.ASSET_SET_MISMATCH)

            val manifest = readBoundedNoFollow(
                stageDir.resolve("manifest.json"), limits.maxMetadataBytes,
            ) ?: return Result.Incomplete(Stop.STAGE_MISSING)
            val observation = ExportUntrustedPrivateStageObservation(
                operationId = operationId,
                noteId = committed.operation.noteId,
                noteRevision = committed.operation.noteRevision,
                snapshotSha256 = committed.binding.snapshotSha256,
                manifestSha256 = ExportSourceBindingDigests.sha256(manifest),
                copies = assets.map { asset ->
                    ExportPrivateCopyObservation(
                        assetId = asset.id, byteCount = asset.encodedBytes,
                        sha256 = asset.sha256, fileName = "asset-" + asset.id + ".bin",
                    )
                }.sortedBy { it.assetId },
            )
            if (ExportPrivateStageReadOnlyVerifier(
                    root.toFile(), limits,
                ).inspect(observation) !is
                ExportPrivateStageReadOnlyVerifier.Assessment.MatchedUntrusted
            ) return Result.Incomplete(Stop.STAGE_INTEGRITY_FAILED)

            // A draft may be missing after a crash. Presence alone is never
            // success; if present it MUST pass the full independent ZIP review.
            val draftPath = stageDir.resolve("draft.notebook")
            val draft = if (Files.exists(draftPath, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    ExportPrivatePortableDraftWriter(
                        root.toFile(), limits, syncDirectory = {
                            throw IOException("Read-only recovery must never sync directories")
                        },
                    ).inspectExistingDraftReadOnly(observation)
                } catch (_: IOException) {
                    return Result.Incomplete(Stop.ARCHIVE_INTEGRITY_FAILED)
                }
            } else null

            // Reconfirm DB evidence after I/O. This check prevents any future
            // incompatible mutable schema from being silently treated as immutable.
            if (readPinned(operationId) != committed) {
                return Result.Incomplete(Stop.DATABASE_CHANGED_OR_UNREADABLE)
            }
            currentCoroutineContext().ensureActive()
            Result.BoundStageUntrusted(observation, draft)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            Result.Incomplete(Stop.DATABASE_CHANGED_OR_UNREADABLE)
        }
    }

    private suspend fun readPinned(operationId: String): Pinned? =
        db.withTransaction {
            currentCoroutineContext().ensureActive()
            val dao = db.exportHoldAdmissionDao()
            val operation = dao.findOperation(operationId) ?: return@withTransaction null
            val binding = dao.findSourceBinding(operationId) ?: return@withTransaction null
            val holds = dao.listHoldsBounded(operationId, limits.maxAssets + 1)
            if (holds.size > limits.maxAssets) {
                throw IOException("Export hold rows exceed recovery limit")
            }
            Pinned(operation, binding, holds.map { it.assetId })
        }

    private suspend fun readBoundedNoFollow(path: Path, limit: Long): ByteArray? =
        withContext(Dispatchers.IO) {
            val before = try {
                Files.readAttributes(path, BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS)
            } catch (_: java.nio.file.NoSuchFileException) { return@withContext null }
            if (!before.isRegularFile || before.isSymbolicLink ||
                before.size() <= 0 || before.size() > limit
            ) throw IOException("Invalid or excessive private evidence file")
            val data = ByteArray(before.size().toInt())
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buf = ByteBuffer.wrap(data)
                while (buf.hasRemaining()) {
                    currentCoroutineContext().ensureActive()
                    if (input.read(buf) <= 0) throw IOException("Truncated private evidence file")
                }
                if (input.size() != data.size.toLong()) throw IOException("Source size changed")
            }
            val after = Files.readAttributes(path, BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS)
            if (!after.isRegularFile || before.size() != after.size() ||
                (before.fileKey() != null && before.fileKey() != after.fileKey())
            ) throw IOException("Private evidence file changed identity")
            data
        }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private companion object {
        val SHA = Regex("[0-9a-f]{64}")
    }
}
