package com.notebook.app.data.assets

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-04: opt-in device-filesystem probe. No Room/database use, no changes to user notes.
 * Each test writes only under its own UUID-named child of Context.filesDir.
 * This checks API/filesystem behavior, NOT power-loss durability or asset registration.
 */
@RunWith(AndroidJUnit4::class)
class ManagedAssetFileStoreAndroidTest {
    private lateinit var sandbox: File

    // A signature-valid placeholder, NOT a fully decoded/validated PNG.
    private val pngHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x00,
    )

    @Before
    fun createIsolatedSandbox(): Unit {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        sandbox = File(context.filesDir, "sto04-device-probe-${UUID.randomUUID()}")
        assertFalse("New probe directory unexpectedly exists", sandbox.exists())
        assertTrue("Could not create private probe directory", sandbox.mkdir())
    }

    @After
    fun removeOnlyProbeSandbox(): Unit {
        if (sandbox.exists()) {
            assertTrue("Could not remove isolated probe directory", sandbox.deleteRecursively())
        }
    }

    @Test
    fun exclusiveCopyPublicationIsReadableAfterStagingIsRemoved(): Unit {
        val store = ManagedAssetFileStore(sandbox)
        val published = store.stageAndFinalize(ByteArrayInputStream(pngHeader), "image/png")
        val finalFile = File(sandbox, published.relativePath)
        assertTrue("Finalized asset missing", finalFile.isFile)
        assertArrayEquals(pngHeader, finalFile.readBytes())
        assertEquals(pngHeader.size.toLong(), published.byteSize)
        assertEquals(sha256(pngHeader), published.sha256)
        assertTrue("Staging not empty", File(sandbox, "staging").listFiles().orEmpty().isEmpty())

        // A second store instance must reuse the same finalized asset without a duplicate.
        val again = ManagedAssetFileStore(sandbox).stageAndFinalize(
            ByteArrayInputStream(pngHeader), "image/png"
        )
        assertEquals(published, again)
        assertEquals(1, File(sandbox, "assets").walkTopDown().count { it.isFile })
    }

    @Test
    fun conflictingFileCannotBeOverwrittenByPublicationRace(): Unit {
        val conflictingBytes = byteArrayOf(9, 8, 7)
        lateinit var conflictedPath: File
        val store = ManagedAssetFileStore(sandbox, beforePublish = { destination ->
            assertFalse(destination.exists())
            conflictedPath = destination
            destination.writeBytes(conflictingBytes)
        })
        try {
            store.stageAndFinalize(ByteArrayInputStream(pngHeader), "image/png")
            fail("Expected a refusal to replace conflicting finalized asset")
        } catch (_: IOException) {
            // Published destination remained untouched, and no DB reference was added.
        }
        assertArrayEquals(conflictingBytes, conflictedPath.readBytes())
        assertTrue("Staging not empty", File(sandbox, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun directoryFsyncIsSupportedForPrivateAssetDirectories(): Unit {
        val published = ManagedAssetFileStore(sandbox).stageAndFinalize(
            ByteArrayInputStream(pngHeader), "image/png"
        )
        val dirs = listOf(
            sandbox,
            File(sandbox, "staging"),
            requireNotNull(File(sandbox, published.relativePath).parentFile),
        )
        for (dir in dirs) {
            assertTrue("Directory unavailable: $dir", dir.isDirectory)
            val fd = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        }
        // The production store now calls directory fsync before returning a new asset.
        // This is an API/filesystem support check, NOT a physical power-loss test.
        assertArrayEquals(pngHeader, File(sandbox, published.relativePath).readBytes())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
