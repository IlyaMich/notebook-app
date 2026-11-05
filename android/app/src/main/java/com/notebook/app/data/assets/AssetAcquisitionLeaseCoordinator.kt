package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * STO-08 C2C-2C: an internal, suspend-safe acquisition lease sharing the existing
 * ManagedAssetFileStore `.asset-publication.lock` OS-lock domain.
 *
 * This is a synchronization primitive, NOT an attachment authorization. A future caller
 * must acquire this lease BEFORE opening its Room transaction, verify bytes and decode
 * media while it is held, and release it AFTER the transaction completes or rolls back.
 *
 * Do not call ManagedAssetRegistrar.register or stageAndFinalize from the lease body:
 * the publisher attempts to acquire the same non-reentrant OS lock. Never invert the
 * logical lock order (Room transaction -> acquisition lease).
 *
 * Only cooperating clients are coordinated. It does not protect against arbitrary
 * modification of app-private files, implement media decoding, or permit cleanup.
 */
internal class AssetAcquisitionLeaseCoordinator(
    private val privateFilesDir: File,
    private val acquisitionTimeoutMillis: Long = 5_000L,
    private val retryDelayMillis: Long = 25L,
) {
    init {
        require(acquisitionTimeoutMillis > 0L) { "Acquisition timeout must be positive" }
        require(retryDelayMillis > 0L) { "Retry delay must be positive" }
    }

    /**
     * Await the shared exclusive OS lock without blocking a JVM monitor or coroutine thread.
     * The timeout applies to acquisition ONLY, never to [action]. Cancellation propagates;
     * all acquired locks/channels are released in finally, even when [action] suspends.
     */
    suspend fun <T> withLease(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        val root = privateFilesDir.toPath()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Expected a real app-private files directory")
        }
        val lockPath = root.resolve(".asset-publication.lock")
        if (Files.isSymbolicLink(lockPath)) {
            throw IOException("Symlink at asset publication lock")
        }

        // NOFOLLOW_LINKS is enforced on open, not just during the preflight check.
        // Creating this coordination file is permitted; no asset/metadata/owner is mutated.
        FileChannel.open(
            lockPath,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Invalid asset publication lock file")
            }
            var acquired: FileLock? = null
            try {
                // Store the lock OUTSIDE withTimeout so even a timeout/cancellation racing
                // with successful tryLock cannot strand an acquired native lock.
                withTimeout(acquisitionTimeoutMillis) {
                    while (acquired == null) {
                        currentCoroutineContext().ensureActive()
                        acquired = try {
                            channel.tryLock()
                        } catch (_: OverlappingFileLockException) {
                            // Another lease or the synchronous publisher owns it in this JVM.
                            null
                        }
                        if (acquired == null) delay(retryDelayMillis)
                    }
                }
                currentCoroutineContext().ensureActive()
                action()
            } finally {
                // No suspending call in this cleanup path; cancellation cannot skip it.
                acquired?.release()
            }
        }
    }
}
