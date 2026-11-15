package com.notebook.app.data.assets

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * I4B-2C-3C-1: Android-only directory fsync adapter for app-private fixture roots.
 * This is a strict sync primitive, not an export seal, recovery decision or receipt.
 * It neither creates nor removes files and does not make an existing stage trusted.
 */
internal object ExportAndroidDirectorySync {
    fun syncOrThrow(directory: File) {
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Cannot fsync missing, linked, or non-directory path")
        }
        val fd = try {
            Os.open(directory.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        } catch (e: ErrnoException) {
            throw IOException("Cannot open directory for fsync", e)
        }
        var failure: IOException? = null
        try {
            try {
                // Check the opened descriptor itself: a path preflight cannot exclude
                // a directory-to-file substitution between isDirectory() and open().
                if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) {
                    failure = IOException("Opened descriptor is not a directory")
                } else {
                    Os.fsync(fd)
                }
            } catch (e: ErrnoException) {
                failure = IOException("Directory fstat or fsync failed", e)
            }
        } finally {
            try {
                Os.close(fd)
            } catch (e: ErrnoException) {
                val closeFailure = IOException("Directory descriptor close failed", e)
                if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
            }
        }
        failure?.let { throw it }
    }
}
