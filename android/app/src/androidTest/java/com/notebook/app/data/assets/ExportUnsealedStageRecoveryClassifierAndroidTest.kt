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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android filesystem fixtures; read-only *classification*, not production recovery.
 * The test owns a unique app-private child directory and never opens managed originals.
 */
@RunWith(AndroidJUnit4::class)
class ExportUnsealedStageRecoveryClassifierAndroidTest {
    private lateinit var root: File

    @Before fun setUp() {
        val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        root = File(filesDir, "sto08-i4b2c3c2-classifier-${UUID.randomUUID()}")
        assertTrue("Cannot create isolated test root", root.mkdir())
    }

    @After fun tearDown() {
        if (::root.isInitialized) {
            assertTrue("Cannot clean isolated test root", root.deleteRecursively())
        }
    }

    private fun policy(snapshot: Long = 128, manifest: Long = 128,
        total: Long = 256, stages: Int = 4) =
        ExportUnsealedStagePolicy(snapshot, manifest, total, stages)

    private fun inspect(limits: ExportUnsealedStagePolicy = policy()) =
        ExportUnsealedStageRecoveryClassifier(root, limits).inspectReadOnly()

    private fun stage(): File {
        val parent = File(root, "export-source-snapshots")
        if (!parent.exists()) assertTrue("Cannot create test staging parent", parent.mkdir())
        return File(parent, UUID.randomUUID().toString()).also {
            assertTrue("Cannot create isolated stage", it.mkdir())
        }
    }

    private fun expectIo(action: () -> Unit) {
        try { action() } catch (_: IOException) { return }
        fail("Expected fail-closed IOException")
    }

    @Test fun absentParentNeverCreatedByScan() {
        val result = inspect()
        assertFalse(result.stagingParentPresent)
        assertTrue(result.findings.isEmpty())
        assertFalse(File(root, "export-source-snapshots").exists())
        assertFalse(File(root, "assets").exists())
    }

    @Test fun snapshotOnlyClassifiedWithoutChangingOriginalBytes() {
        val dir = stage()
        val payload = byteArrayOf(1, 2, 3, 4)
        val snapshot = File(dir, "snapshot.bin")
        snapshot.writeBytes(payload)
        val finding = inspect().findings.single()
        assertEquals(UUID.fromString(dir.name), finding.operationId)
        assertEquals(UnsealedStageRecoveryShape.SNAPSHOT_ONLY, finding.shape)
        assertEquals(4L, finding.snapshotBytes)
        assertEquals(null, finding.manifestBytes)
        assertArrayEquals(payload, snapshot.readBytes())
        assertEquals(setOf("snapshot.bin"), dir.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun manifestOnlyClassifiedAsUntrusted() {
        val dir = stage()
        File(dir, "manifest.bin").writeBytes(byteArrayOf(11, 12))
        val finding = inspect().findings.single()
        assertEquals(UnsealedStageRecoveryShape.MANIFEST_ONLY, finding.shape)
        assertEquals(null, finding.snapshotBytes)
        assertEquals(2L, finding.manifestBytes)
        assertFalse(File(dir, "seal").exists())
    }

    @Test fun bothFilesPresentStillUnsealedWithoutContentReads() {
        val dir = stage()
        val originalSnapshot = byteArrayOf(3, 4, 5)
        val originalManifest = byteArrayOf(6, 7)
        val snapshot = File(dir, "snapshot.bin").also { it.writeBytes(originalSnapshot) }
        val manifest = File(dir, "manifest.bin").also { it.writeBytes(originalManifest) }
        val finding = inspect().findings.single()
        assertEquals(UnsealedStageRecoveryShape.BOTH_FILES_PRESENT, finding.shape)
        assertEquals(3L, finding.snapshotBytes)
        assertEquals(2L, finding.manifestBytes)
        assertArrayEquals(originalSnapshot, snapshot.readBytes())
        assertArrayEquals(originalManifest, manifest.readBytes())
        assertFalse(File(dir, "seal").exists())
        assertFalse(File(root, "assets").exists())
    }

    @Test fun unexpectedSealEntryFailsClosedWithoutRemoval() {
        val dir = stage()
        val seal = File(dir, "seal")
        seal.writeText("untrusted")
        expectIo { inspect() }
        assertEquals("untrusted", seal.readText())
        assertTrue(dir.isDirectory)
    }

    @Test fun linkedStageRejectedWithoutTouchingRealDirectory() {
        val real = stage()
        val sentinel = File(real, "snapshot.bin").also { it.writeBytes(byteArrayOf(42)) }
        val linked = File(real.parentFile!!, UUID.randomUUID().toString())
        Files.createSymbolicLink(linked.toPath(), real.toPath())
        try {
            expectIo { inspect() }
            assertArrayEquals(byteArrayOf(42), sentinel.readBytes())
            assertTrue(real.isDirectory)
        } finally {
            Files.delete(linked.toPath())
        }
    }

    @Test fun linkedPayloadRejectedWithoutOpeningTarget() {
        val dir = stage()
        val target = File(root, "test-only-sentinel").also { it.writeText("preserve") }
        val linked = File(dir, "snapshot.bin")
        Files.createSymbolicLink(linked.toPath(), target.toPath())
        try {
            expectIo { inspect() }
            assertEquals("preserve", target.readText())
        } finally {
            Files.delete(linked.toPath())
        }
    }

    @Test fun aggregateBudgetViolationDoesNotRemoveFiles() {
        val dir = stage()
        val snapshot = File(dir, "snapshot.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val manifest = File(dir, "manifest.bin").also { it.writeBytes(byteArrayOf(4, 5, 6)) }
        expectIo { inspect(policy(snapshot = 4, manifest = 4, total = 5)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), snapshot.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), manifest.readBytes())
    }

    @Test fun stageCountOverflowLeavesAllExistingStages() {
        val a = stage()
        val b = stage()
        expectIo { inspect(policy(stages = 1)) }
        assertTrue(a.isDirectory)
        assertTrue(b.isDirectory)
        assertEquals(2, File(root, "export-source-snapshots").listFiles()!!.size)
    }

    @Test fun nonDirectoryParentRejectedWithoutModification() {
        val parent = File(root, "export-source-snapshots")
        parent.writeText("not-a-directory")
        expectIo { inspect() }
        assertEquals("not-a-directory", parent.readText())
        assertFalse(File(root, "assets").exists())
    }
}
