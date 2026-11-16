package com.notebook.app.data.assets

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Os.open/fsync/close fixture tests; no managed assets or production stage. */
@RunWith(AndroidJUnit4::class)
class ExportAndroidDirectorySyncAndroidTest {
    private lateinit var root: File

    @Before fun setUp() {
        val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        root = File(files, "sto08-i4b2c3c1-sync-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
    }

    @After fun tearDown() {
        if (::root.isInitialized) assertTrue(root.deleteRecursively())
    }

    private fun expectIo(action: () -> Unit) {
        try { action() } catch (_: IOException) { return }
        fail("Expected IOException")
    }

    @Test fun syncsExistingPrivateFixtureDirectory() {
        ExportAndroidDirectorySync.syncOrThrow(root)
        assertTrue(root.isDirectory)
    }

    @Test fun syncsNestedDirectoryWithExistingEntry() {
        val nested = File(root, "nested")
        assertTrue(nested.mkdir())
        File(nested, "marker").writeText("sentinel")
        ExportAndroidDirectorySync.syncOrThrow(nested)
        assertEquals("sentinel", File(nested, "marker").readText())
    }

    @Test fun syncsParentDirectoryWithoutMutatingExistingFile() {
        val marker = File(root, "sentinel.bin")
        val original = byteArrayOf(1, 0, 3, 7)
        marker.writeBytes(original)
        ExportAndroidDirectorySync.syncOrThrow(root)
        assertArrayEquals(original, marker.readBytes())
    }

    @Test fun rejectsMissingDirectoryWithoutCreatingIt() {
        val absent = File(root, "missing")
        expectIo { ExportAndroidDirectorySync.syncOrThrow(absent) }
        assertTrue(!absent.exists())
    }

    @Test fun rejectsRegularFileWithoutChangingBytes() {
        val file = File(root, "not-a-directory")
        file.writeBytes(byteArrayOf(9, 8))
        expectIo { ExportAndroidDirectorySync.syncOrThrow(file) }
        assertArrayEquals(byteArrayOf(9, 8), file.readBytes())
    }

    @Test fun rejectsSymbolicLinkWithoutTouchingTarget() {
        val target = File(root, "actual")
        assertTrue(target.mkdir())
        val link = File(root, "alias")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: UnsupportedOperationException) {
            assumeNoException(e)
        } catch (e: java.nio.file.FileSystemException) {
            assumeNoException(e)
        } catch (e: SecurityException) {
            assumeNoException(e)
        }
        expectIo { ExportAndroidDirectorySync.syncOrThrow(link) }
        assertTrue(target.isDirectory)
    }
}
