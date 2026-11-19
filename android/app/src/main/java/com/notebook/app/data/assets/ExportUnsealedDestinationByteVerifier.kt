package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Independent, bounded observations of SYNTHETIC, UNSEALED staging bytes only. */
internal data class UntrustedStageByteObservation(
    val operationId: UUID,
    val role: ExportUnsealedDestinationByteVerifier.Role,
    val byteCount: Long,
    val sha256: String,
)

/**
 * V5-R34-C1: strictly read-only diagnostic for an already-existing synthetic stage.
 * Does not create/adopt/seal/repair/delete stages; no Room, managed originals, lease,
 * production export wiring, fsync, receipt, hold release, VERIFIED promotion, or GC.
 *
 * Caller supplies INDEPENDENT expected bytes/digest and explicit synthetic fixture
 * budgets. Matching observed bytes establish neither source authenticity nor durable
 * protection. Path/attribute checks do NOT make an atomic snapshot against concurrent
 * mutation or malicious ancestor substitution; never use this for trust decisions.
 */
internal class ExportUnsealedDestinationByteVerifier(
    syntheticFixtureRoot: File,
    private val policy: ExportUnsealedStagePolicy,
    private val afterChunkRead: (Long) -> Unit = {}, // deterministic JVM fault/cancellation seam
) {
    internal enum class Role(val fileName: String) {
        SNAPSHOT("snapshot.bin"), MANIFEST("manifest.bin"),
    }

    internal data class Expectation(
        val operationId: UUID,
        val role: Role,
        val byteCount: Long,
        val sha256: String,
    )

    internal enum class Reason {
        INVALID_EXPECTATION, BUDGET_EXCEEDED, UNSAFE_DIRECTORY, MISSING_FILE,
        UNSAFE_FILE, BYTES_MISMATCH, IDENTITY_UNAVAILABLE, FILE_CHANGED, READ_FAILED,
    }

    internal sealed interface Assessment {
        data class ObservedUntrusted(val observation: UntrustedStageByteObservation) : Assessment
        data class Rejected(val reason: Reason) : Assessment
    }

    private val root: Path = syntheticFixtureRoot.toPath().toAbsolutePath().normalize()
    private val parent: Path = root.resolve("export-source-snapshots")

    suspend fun inspect(expectation: Expectation): Assessment = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val roleBudget = when (expectation.role) {
            Role.SNAPSHOT -> policy.maxSnapshotBytes
            Role.MANIFEST -> policy.maxManifestBytes
        }
        if (expectation.byteCount < 0L || !SHA256.matches(expectation.sha256)) {
            return@withContext Assessment.Rejected(Reason.INVALID_EXPECTATION)
        }
        if (expectation.byteCount > roleBudget || expectation.byteCount > policy.maxStageBytes) {
            return@withContext Assessment.Rejected(Reason.BUDGET_EXCEEDED)
        }

        val stage = parent.resolve(expectation.operationId.toString())
        val target = stage.resolve(expectation.role.fileName)
        try {
            val rootBefore = directoryAttributes(root)
                ?: return@withContext Assessment.Rejected(Reason.UNSAFE_DIRECTORY)
            val parentBefore = directoryAttributes(parent)
                ?: return@withContext Assessment.Rejected(Reason.UNSAFE_DIRECTORY)
            val stageBefore = directoryAttributes(stage)
                ?: return@withContext Assessment.Rejected(Reason.UNSAFE_DIRECTORY)
            val before = try {
                Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return@withContext Assessment.Rejected(Reason.MISSING_FILE)
            }
            if (!before.isRegularFile || before.isSymbolicLink) {
                return@withContext Assessment.Rejected(Reason.UNSAFE_FILE)
            }
            if (before.size() > roleBudget || before.size() > policy.maxStageBytes) {
                return@withContext Assessment.Rejected(Reason.BUDGET_EXCEEDED)
            }
            if (before.size() != expectation.byteCount) {
                return@withContext Assessment.Rejected(Reason.BYTES_MISMATCH)
            }
            // fileKey() is optional on supported JVM file providers (including Windows).
            // Missing keys cannot prove stable identity; this diagnostic only returns an
            // explicitly UNTRUSTED observation, never publication or ownership authority.

            val digest = MessageDigest.getInstance("SHA-256")
            var bytesRead = 0L
            val chunk = ByteArray(CHUNK_SIZE)
            val readResult: Reason? = try {
                FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    if (channel.size() != expectation.byteCount) return@use Reason.BYTES_MISMATCH
                    val buffer = ByteBuffer.wrap(chunk)
                    while (true) {
                        context.ensureActive()
                        buffer.clear()
                        val size = channel.read(buffer)
                        if (size == -1) break
                        if (size == 0) return@use Reason.READ_FAILED
                        if (size.toLong() > expectation.byteCount - bytesRead) {
                            return@use Reason.BYTES_MISMATCH
                        }
                        digest.update(chunk, 0, size)
                        bytesRead += size.toLong()
                        afterChunkRead(bytesRead)
                        context.ensureActive()
                    }
                    if (bytesRead != expectation.byteCount || channel.size() != expectation.byteCount) {
                        Reason.BYTES_MISMATCH
                    } else null
                }
            } finally {
                chunk.fill(0)
            }
            if (readResult != null) return@withContext Assessment.Rejected(readResult)

            // Verify stable path identities AFTER closing the read-only descriptor.
            val after = try {
                Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return@withContext Assessment.Rejected(Reason.FILE_CHANGED)
            }
            val rootAfter = directoryAttributes(root)
            val parentAfter = directoryAttributes(parent)
            val stageAfter = directoryAttributes(stage)
            if (rootAfter == null || parentAfter == null || stageAfter == null ||
                !stable(rootBefore, rootAfter) || !stable(parentBefore, parentAfter) ||
                !stable(stageBefore, stageAfter) || !stable(before, after)
            ) return@withContext Assessment.Rejected(Reason.FILE_CHANGED)

            val actualSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (actualSha != expectation.sha256) {
                return@withContext Assessment.Rejected(Reason.BYTES_MISMATCH)
            }
            context.ensureActive()
            Assessment.ObservedUntrusted(UntrustedStageByteObservation(
                expectation.operationId, expectation.role, bytesRead, actualSha,
            ))
        } catch (missing: NoSuchFileException) {
            Assessment.Rejected(Reason.FILE_CHANGED)
        } catch (_: IOException) {
            context.ensureActive()
            Assessment.Rejected(Reason.READ_FAILED)
        } catch (_: SecurityException) {
            context.ensureActive()
            Assessment.Rejected(Reason.READ_FAILED)
        }
    }

    private fun directoryAttributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            .takeIf { it.isDirectory && !it.isSymbolicLink }
    } catch (_: NoSuchFileException) {
        null
    }

    private fun stable(before: BasicFileAttributes, after: BasicFileAttributes): Boolean {
        val beforeKey = before.fileKey()
        val afterKey = after.fileKey()
        // A present key must remain equal. A key appearing/disappearing is unstable.
        // Two null keys provide NO identity guarantee; fall back to metadata comparison
        // only for this untrusted, synthetic-stage diagnostic (never a trust gate).
        val keysCompatible = if (beforeKey == null || afterKey == null) {
            beforeKey == null && afterKey == null
        } else {
            beforeKey == afterKey
        }
        return keysCompatible &&
            before.isRegularFile == after.isRegularFile &&
            before.isDirectory == after.isDirectory &&
            before.size() == after.size() &&
            before.lastModifiedTime() == after.lastModifiedTime()
    }

    private companion object {
        const val CHUNK_SIZE = 8_192
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
