package com.notebook.app.data.assets

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class ManagedAssetFileStoreTest {
    @get:Rule val root = TemporaryFolder()

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x00,
    )

    @Test fun stagesAndFinalizesUsingRelativeHashPath() {
        val store = ManagedAssetFileStore(root.root, directorySync = {})
        val result = store.stageAndFinalize(ByteArrayInputStream(png), "image/png")
        assertEquals("image/png", result.mimeType)
        assertEquals(png.size.toLong(), result.byteSize)
        assertTrue(result.relativePath.matches(Regex("assets/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{64}\\.png")))
        assertArrayEquals(png, root.root.resolve(result.relativePath).readBytes())
        assertTrue(root.root.resolve("staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun duplicateBytesReuseTheSameFinalFile() {
        val store = ManagedAssetFileStore(root.root, directorySync = {})
        val first = store.stageAndFinalize(ByteArrayInputStream(png), "image/png")
        val second = store.stageAndFinalize(ByteArrayInputStream(png), "image/png")
        assertEquals(first, second)
        assertEquals(1, root.root.resolve("assets").walkTopDown().count { it.isFile })
    }

    @Test fun rejectsMimeMismatchWithoutLeavingStagedOrFinalBytes() {
        val store = ManagedAssetFileStore(root.root, directorySync = {})
        assertThrowsIo { store.stageAndFinalize(ByteArrayInputStream(png), "application/pdf") }
        assertFalse(root.root.resolve("assets").exists())
        assertTrue(root.root.resolve("staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun rejectsUnsupportedMimeAndTooLargeAsset() {
        assertThrowsIo {
            ManagedAssetFileStore(root.root, directorySync = {}).stageAndFinalize(ByteArrayInputStream(png), "text/plain")
        }
        assertThrowsIo {
            ManagedAssetFileStore(root.root, maximumBytes = 5, directorySync = {}).stageAndFinalize(
                ByteArrayInputStream(png), "image/png"
            )
        }
        assertTrue(root.root.resolve("staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun interruptedInputDeletesStageAndNeverCreatesFinalFile() {
        val broken = object : InputStream() {
            var emitted = 0
            override fun read(): Int {
                if (emitted++ >= 8) throw IOException("simulated interrupted import")
                return png[emitted - 1].toInt() and 0xff
            }
        }
        assertThrowsIo { ManagedAssetFileStore(root.root, directorySync = {}).stageAndFinalize(broken, "image/png") }
        assertTrue(root.root.resolve("staging").listFiles().orEmpty().isEmpty())
        assertFalse(root.root.resolve("assets").exists())
    }

    @Test fun preexistingCorruptContentAddressedFileIsNeverOverwritten() {
        val store = ManagedAssetFileStore(root.root, directorySync = {})
        val result = store.stageAndFinalize(ByteArrayInputStream(png), "image/png")
        val file = root.root.resolve(result.relativePath)
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertThrowsIo { store.stageAndFinalize(ByteArrayInputStream(png), "image/png") }
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertTrue(root.root.resolve("staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun concurrentPublicationCannotOverwriteAConflictingDestination() {
        val conflicting = byteArrayOf(9, 8, 7)
        var destination: java.io.File? = null
        val store = ManagedAssetFileStore(root.root, directorySync = {}, beforePublish = { finalFile ->
            // Simulate another writer publishing after the pre-check but before CREATE_NEW.
            assertFalse(finalFile.exists())
            destination = finalFile
            finalFile.writeBytes(conflicting)
        })
        assertThrowsIo { store.stageAndFinalize(ByteArrayInputStream(png), "image/png") }
        assertArrayEquals(conflicting, requireNotNull(destination).readBytes())
        assertTrue(root.root.resolve("staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun interruptedExclusiveCopyDeletesOwnedPartialFinalFile() {
        var reserved: java.io.File? = null
        val store = ManagedAssetFileStore(
            root.root,
            beforePublish = { reserved = it },
            afterPublishBytesWritten = { throw IOException("simulated copy failure") },
            directorySync = {},
        )
        assertThrowsIo { store.stageAndFinalize(ByteArrayInputStream(png), "image/png") }
        assertFalse(requireNotNull(reserved).exists())
        assertTrue(root.root.resolve("staging").listFiles().orEmpty().isEmpty())
    }


    @Test fun directoryEntriesAreSyncedBeforeSuccessfulReturn() {
        val synced = mutableListOf<File>()
        val store = ManagedAssetFileStore(root.root, directorySync = { synced.add(it) })
        val result = store.stageAndFinalize(ByteArrayInputStream(png), "image/png")
        val finalFile = File(root.root, result.relativePath)
        assertEquals(finalFile.parentFile, synced.last())
        assertTrue(synced.contains(root.root))
        assertTrue(synced.contains(File(root.root, "assets")))
        assertTrue(synced.contains(finalFile.parentFile?.parentFile))
    }

    @Test fun finalDirectorySyncFailureDoesNotAcknowledgeFile() {
        var reserved: File? = null
        var denied = false
        val store = ManagedAssetFileStore(
            root.root,
            beforePublish = { reserved = it },
            directorySync = { directory ->
                if (directory == reserved?.parentFile) {
                    denied = true
                    throw IOException("simulated directory fsync failure")
                }
            },
        )
        assertThrowsIo { store.stageAndFinalize(ByteArrayInputStream(png), "image/png") }
        assertTrue(denied)
        // A complete but unregistered orphan may remain. A retry verifies and reuses it.
        assertArrayEquals(png, requireNotNull(reserved).readBytes())
        val retry = ManagedAssetFileStore(root.root, directorySync = {})
            .stageAndFinalize(ByteArrayInputStream(png), "image/png")
        assertEquals(requireNotNull(reserved), File(root.root, retry.relativePath))
    }

    private fun assertThrowsIo(block: () -> Unit) {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
            // Expected: rejection without publishing database references.
        }
    }
}
