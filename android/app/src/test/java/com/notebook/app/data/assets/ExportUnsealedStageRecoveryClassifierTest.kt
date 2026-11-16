package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Read-only synthetic filesystem fixtures only. No Android, Room, lease or export. */
class ExportUnsealedStageRecoveryClassifierTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root(): File = temporary.newFolder()
    private fun policy(
        snapshot: Long = 128,
        manifest: Long = 128,
        aggregate: Long = 256,
        stages: Int = 4,
    ) = ExportUnsealedStagePolicy(snapshot, manifest, aggregate, stages)
    private fun stage(root: File, name: String = UUID.randomUUID().toString()): File {
        val parent = File(root, "export-source-snapshots")
        if (!parent.exists()) assertTrue(parent.mkdir())
        return File(parent, name).also { assertTrue(it.mkdir()) }
    }
    private fun inspect(root: File, limits: ExportUnsealedStagePolicy = policy()) =
        ExportUnsealedStageRecoveryClassifier(root, limits).inspectReadOnly()
    private fun expectIo(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        fail("Expected fail-closed IOException")
    }

    @Test fun absentParentRemainsAbsentAndIsNotCreated() {
        val root = root()
        val result = inspect(root)
        assertFalse(result.stagingParentPresent)
        assertTrue(result.findings.isEmpty())
        assertFalse(File(root, "export-source-snapshots").exists())
    }

    @Test fun emptyParentReturnsNoStages() {
        val root = root()
        assertTrue(File(root, "export-source-snapshots").mkdir())
        val result = inspect(root)
        assertTrue(result.stagingParentPresent)
        assertTrue(result.findings.isEmpty())
    }

    @Test fun emptyUuidStageRemainsUnsealedAndUntouched() {
        val root = root()
        val stage = stage(root)
        val finding = inspect(root).findings.single()
        assertEquals(UUID.fromString(stage.name), finding.operationId)
        assertEquals(UnsealedStageRecoveryShape.EMPTY, finding.shape)
        assertEquals(null, finding.snapshotBytes)
        assertEquals(null, finding.manifestBytes)
        assertTrue(stage.listFiles().isNullOrEmpty())
    }

    @Test fun snapshotOnlyIsReportedWithoutReadingOrChangingBytes() {
        val root = root()
        val stage = stage(root)
        val original = byteArrayOf(1, 2, 3)
        File(stage, "snapshot.bin").writeBytes(original)
        val finding = inspect(root).findings.single()
        assertEquals(UnsealedStageRecoveryShape.SNAPSHOT_ONLY, finding.shape)
        assertEquals(3L, finding.snapshotBytes)
        assertEquals(null, finding.manifestBytes)
        assertArrayEquals(original, File(stage, "snapshot.bin").readBytes())
    }

    @Test fun manifestOnlyRemainsUntrusted() {
        val root = root()
        val stage = stage(root)
        File(stage, "manifest.bin").writeBytes(byteArrayOf(1, 0))
        val finding = inspect(root).findings.single()
        assertEquals(UnsealedStageRecoveryShape.MANIFEST_ONLY, finding.shape)
        assertEquals(2L, finding.manifestBytes)
        assertEquals(null, finding.snapshotBytes)
    }

    @Test fun bothFilesPresentNeverImplyProtectedExport() {
        val root = root()
        val stage = stage(root)
        File(stage, "snapshot.bin").writeBytes(byteArrayOf(7, 8))
        File(stage, "manifest.bin").writeBytes(byteArrayOf(9))
        val finding = inspect(root).findings.single()
        assertEquals(UnsealedStageRecoveryShape.BOTH_FILES_PRESENT, finding.shape)
        assertEquals(2L, finding.snapshotBytes)
        assertEquals(1L, finding.manifestBytes)
        assertFalse(File(stage, "seal").exists())
    }

    @Test fun multipleStagesReturnStableCanonicalOrder() {
        val root = root()
        val high = stage(root, "ffffffff-ffff-4fff-bfff-ffffffffffff")
        val low = stage(root, "00000000-0000-4000-8000-000000000001")
        File(high, "snapshot.bin").writeBytes(byteArrayOf(1))
        val stages = inspect(root).findings
        assertEquals(2, stages.size)
        assertEquals(UUID.fromString(low.name), stages[0].operationId)
        assertEquals(UUID.fromString(high.name), stages[1].operationId)
    }

    @Test fun unexpectedStageParentFileFailsWithoutModification() {
        val root = root()
        val parent = File(root, "export-source-snapshots")
        parent.writeText("sentinel")
        expectIo { inspect(root) }
        assertEquals("sentinel", parent.readText())
    }

    @Test fun malformedOrUnexpectedStageEntryIsRejectedWithoutDeletion() {
        val root = root()
        val bad = stage(root, "not-a-uuid")
        File(bad, "unknown").writeText("sentinel")
        expectIo { inspect(root) }
        assertEquals("sentinel", File(bad, "unknown").readText())
    }

    @Test fun extraSealEntryIsNotTreatedAsProtected() {
        val root = root()
        val stage = stage(root)
        File(stage, "snapshot.bin").writeBytes(byteArrayOf(1))
        File(stage, "manifest.bin").writeBytes(byteArrayOf(2))
        File(stage, "seal").writeBytes(byteArrayOf(3))
        expectIo { inspect(root) }
        assertTrue(File(stage, "seal").exists())
    }

    @Test fun nonregularPayloadDirectoryFailsClosed() {
        val root = root()
        val stage = stage(root)
        assertTrue(File(stage, "snapshot.bin").mkdir())
        expectIo { inspect(root) }
        assertTrue(File(stage, "snapshot.bin").isDirectory)
    }

    @Test fun snapshotExceedingPerFileBudgetFailsWithoutModification() {
        val root = root()
        val stage = stage(root)
        File(stage, "snapshot.bin").writeBytes(byteArrayOf(1, 2, 3, 4))
        expectIo { inspect(root, policy(snapshot = 3)) }
        assertEquals(4L, File(stage, "snapshot.bin").length())
    }

    @Test fun aggregateLimitRejectsEvenWhenIndividualSizesFit() {
        val root = root()
        val stage = stage(root)
        File(stage, "snapshot.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(stage, "manifest.bin").writeBytes(byteArrayOf(4, 5, 6))
        expectIo { inspect(root, policy(snapshot = 4, manifest = 4, aggregate = 5)) }
        assertTrue(File(stage, "snapshot.bin").exists())
    }

    @Test fun stageCountLimitRejectsWithoutDeletingStages() {
        val root = root()
        val first = stage(root)
        val second = stage(root)
        expectIo { inspect(root, policy(stages = 1)) }
        assertTrue(first.exists())
        assertTrue(second.exists())
    }

    @Test fun symbolicStageDirectoryRejectedWithoutTouchingTarget() {
        val root = root()
        val existing = stage(root)
        val alias = File(existing.parentFile, UUID.randomUUID().toString())
        try {
            Files.createSymbolicLink(alias.toPath(), existing.toPath())
        } catch (unsupported: UnsupportedOperationException) {
            assumeNoException(unsupported)
        } catch (noPrivilege: java.nio.file.FileSystemException) {
            assumeNoException(noPrivilege)
        } catch (security: SecurityException) {
            assumeNoException(security)
        }
        expectIo { inspect(root) }
        assertTrue(existing.isDirectory)
    }

    @Test fun symbolicPayloadRejectedWithoutOpeningContents() {
        val root = root()
        val stage = stage(root)
        val real = File(root, "outside-original").also { it.writeText("keep") }
        val link = File(stage, "snapshot.bin")
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
        } catch (unsupported: UnsupportedOperationException) {
            assumeNoException(unsupported)
        } catch (noPrivilege: java.nio.file.FileSystemException) {
            assumeNoException(noPrivilege)
        } catch (security: SecurityException) {
            assumeNoException(security)
        }
        expectIo { inspect(root) }
        assertEquals("keep", real.readText())
    }
}
