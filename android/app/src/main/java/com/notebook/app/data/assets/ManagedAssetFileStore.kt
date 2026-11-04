package com.notebook.app.data.assets

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale

/** Android production directory sync; JVM tests inject a deterministic fake. */
private fun syncAndroidDirectory(directory: File) {
    val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(fd)
    } finally {
        Os.close(fd)
    }
}

/**
 * STO-04, file-only foundation. Callers must supply an app-private persistent files directory
 * (Context.filesDir), never cacheDir or an externally controlled directory.
 *
 * This does not register database references or authorize deletion. File and directory
 * sync ordering is implemented, but DB coordination and crash reconciliation are separate
 * STO-04 checkpoints.
 * Requires Android API 26+ for FileChannel.open(CREATE_NEW).
 */
internal class ManagedAssetFileStore(
    private val privateFilesDir: File,
    private val maximumBytes: Long = 64L * 1024L * 1024L,
    /** Fault hook for deterministic TOCTOU tests; production callers leave this null. */
    private val beforePublish: ((File) -> Unit)? = null,
    /** Fault hook; may throw after an exclusive destination has been partly written. */
    private val afterPublishBytesWritten: ((Long) -> Unit)? = null,
    /** Test seam: JVM unit tests use a fake; Android production syncs real directories. */
    private val directorySync: (File) -> Unit = ::syncAndroidDirectory,
) {
    init {
        require(maximumBytes > 0) { "maximumBytes must be positive" }
    }

    data class FinalizedFile(
        val sha256: String,
        val mimeType: String,
        val byteSize: Long,
        val relativePath: String,
    )

    /** Only checks the file signature; full raster/PDF decoding must precede DB publication. */
    private enum class Type(val mime: String, val extension: String) {
        PNG("image/png", "png"), JPEG("image/jpeg", "jpg"), PDF("application/pdf", "pdf");

        fun matchesSignature(firstBytes: ByteArray, collected: Int): Boolean = when (this) {
            PNG -> collected >= 8 && firstBytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10)
                .map { it.toByte() }
            JPEG -> collected >= 3 && firstBytes[0] == 0xff.toByte() &&
                firstBytes[1] == 0xd8.toByte() && firstBytes[2] == 0xff.toByte()
            PDF -> collected >= 5 && firstBytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))
        }
    }

    /**
     * Stage, hash, verify signature, then exclusively CREATE_NEW a final destination and copy
     * the staged bytes there. Only the creation is atomic/non-overwriting: the copy is not
     * atomic or crash-safe, and other concurrent importers may temporarily see an incomplete
     * file and must fail closed. This method returns only after the final file is synced and
     * hash-verified and the final directory entry has been synced. DB registration and
     * orphan repair are NOT implemented.
     * Never expose the temporary staging path or an in-progress final file as a DB reference.
     */
    @Synchronized
    fun stageAndFinalize(input: InputStream, declaredMimeType: String): FinalizedFile {
        val normalizedMime = declaredMimeType.trim().lowercase(Locale.ROOT)
        val type = Type.entries.firstOrNull { it.mime == normalizedMime }
            ?: throw IOException("Unsupported asset MIME type")
        if (!privateFilesDir.isDirectory) {
            throw IOException("Expected existing private files directory")
        }
        val stagingDir = ensureChildDirectory(privateFilesDir, "staging")
        val staged = File.createTempFile("asset-", ".partial", stagingDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var length = 0L
            val firstBytes = ByteArray(8)
            var headerLength = 0
            FileOutputStream(staged).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (count.toLong() > maximumBytes - length) {
                        throw IOException("Asset exceeds configured byte limit")
                    }
                    if (headerLength < firstBytes.size) {
                        val copy = minOf(count, firstBytes.size - headerLength)
                        buffer.copyInto(firstBytes, headerLength, 0, copy)
                        headerLength += copy
                    }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    length += count.toLong()
                }
                output.fd.sync()
            }
            if (!type.matchesSignature(firstBytes, headerLength)) {
                throw IOException("Asset MIME does not match its file signature")
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val relativePath = "assets/${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash.${type.extension}"
            // Persist each newly created ancestor entry before making a DB reference possible.
            val assets = ensureChildDirectory(privateFilesDir, "assets")
            val first = ensureChildDirectory(assets, hash.substring(0, 2))
            val parent = ensureChildDirectory(first, hash.substring(2, 4))
            val finalFile = File(parent, "$hash.${type.extension}")
            var createdFinal = false
            if (!finalFile.exists()) {
                // The fast-path exists() check is not the concurrency guard. CREATE_NEW is:
                // it atomically refuses to open an existing file (even a symbolic link).
                // Android API 37 rejected hard links in app-private storage; do not use them.
                beforePublish?.invoke(finalFile)
                val freshChannel = try {
                    FileChannel.open(
                        finalFile.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW
                    )
                } catch (_: FileAlreadyExistsException) {
                    // A concurrent importer won; do not alter its destination.
                    null
                }
                if (freshChannel != null) {
                    try {
                        freshChannel.use { destination ->
                            staged.inputStream().use { source ->
                                val buffer = ByteArray(8192)
                                var written = 0L
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    val bytes = ByteBuffer.wrap(buffer, 0, count)
                                    while (bytes.hasRemaining()) destination.write(bytes)
                                    written += count.toLong()
                                    afterPublishBytesWritten?.invoke(written)
                                }
                            }
                            destination.force(true)
                        }
                        createdFinal = true
                    } catch (failure: Throwable) {
                        // We exclusively created this destination. Failed copies must not
                        // remain as apparently finalized artifacts in the live process.
                        try {
                            Files.deleteIfExists(finalFile.toPath())
                        } catch (cleanup: IOException) {
                            failure.addSuppressed(cleanup)
                        }
                        throw failure
                    }
                }
            }
            // Do not follow a symbolic link in the final location. All paths are generated
            // from our digest, but app-private corruption must not redirect verification.
            if (!Files.isRegularFile(finalFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                finalFile.length() != length || sha256(finalFile) != hash
            ) {
                throw IOException("Existing content-addressed asset is corrupt")
            }
            // An acknowledged file must have both synced bytes and a durable directory entry.
            // A failure here may leave an unreferenced complete file for later reconciliation;
            // fail closed rather than falsely returning FinalizedFile.
            if (createdFinal) syncDirectoryOrThrow(parent)
            return FinalizedFile(hash, type.mime, length, relativePath)
        } finally {
            // Removing an abandoned stage is always safe; finalized originals are never deleted here.
            staged.delete()
        }
    }

    /** Create one controlled child directory, then persist its parent's new entry. */
    private fun ensureChildDirectory(parent: File, name: String): File {
        val child = File(parent, name)
        if (Files.isSymbolicLink(child.toPath())) {
            throw IOException("Symlink in private asset directory tree")
        }
        if (!child.isDirectory) {
            if (child.mkdir()) {
                syncDirectoryOrThrow(parent)
            } else if (!child.isDirectory || Files.isSymbolicLink(child.toPath())) {
                throw IOException("Cannot create app-private asset directory")
            }
        }
        return child
    }

    private fun syncDirectoryOrThrow(directory: File) {
        try {
            directorySync(directory)
        } catch (failure: Exception) {
            throw IOException("Failed to sync asset directory entry", failure)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
