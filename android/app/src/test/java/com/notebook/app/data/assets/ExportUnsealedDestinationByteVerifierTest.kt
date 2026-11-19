package com.notebook.app.data.assets

import com.notebook.app.data.assets.ExportUnsealedDestinationByteVerifier as Verifier
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic read-only JVM fixtures. No managed originals, Room, leases, receipts or GC. */
class ExportUnsealedDestinationByteVerifierTest {
    @get:Rule val temp = TemporaryFolder()

    private val operationId = UUID.fromString("abcdef00-0000-4000-8000-000000000002")
    private fun root(): File = temp.newFolder("synthetic")
    private fun stage(root: File, id: UUID = operationId): File =
        File(root, "export-source-snapshots/$id").also { assertTrue(it.mkdirs()) }
    private fun bytes(size: Int = 8_193) = ByteArray(size) { (it % 251).toByte() }
    private fun sha(raw: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(raw).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun limits(snap: Long = 16_384, manifest: Long = 16_384, stage: Long = 32_768) =
        ExportUnsealedStagePolicy(snap, manifest, stage, 4)
    private fun expected(raw: ByteArray, role: Verifier.Role = Verifier.Role.SNAPSHOT,
        id: UUID = operationId,
    ) = Verifier.Expectation(id, role, raw.size.toLong(), sha(raw))
    private fun inspect(root: File, expectation: Verifier.Expectation,
        policy: ExportUnsealedStagePolicy = limits(), after: (Long) -> Unit = {},
    ) = runBlocking { Verifier(root, policy, after).inspect(expectation) }
    private fun write(root: File, raw: ByteArray,
        role: Verifier.Role = Verifier.Role.SNAPSHOT): File =
        File(stage(root), role.fileName).also { it.writeBytes(raw) }
    private fun rejected(result: Verifier.Assessment, reason: Verifier.Reason) =
        assertEquals(Verifier.Assessment.Rejected(reason), result)
    private fun link(link: Path, to: Path) {
        try {
            Files.createSymbolicLink(link, to)
        } catch (unsupported: UnsupportedOperationException) {
            assumeNoException(unsupported)
        } catch (denied: java.nio.file.FileSystemException) {
            assumeNoException(denied)
        } catch (denied: SecurityException) {
            assumeNoException(denied)
        }
    }

    @Test fun snapshotBytesAreIndependentlyReadAndRemainExplicitlyUntrusted() {
        val dir = root()
        val raw = bytes()
        val file = write(dir, raw)
        val answer = inspect(dir, expected(raw))
        assertEquals(Verifier.Assessment.ObservedUntrusted(UntrustedStageByteObservation(
            operationId, Verifier.Role.SNAPSHOT, raw.size.toLong(), sha(raw),
        )), answer)
        assertArrayEquals(raw, file.readBytes())
        assertFalse(File(dir, "seal").exists())
        assertFalse(File(dir, ".asset-publication.lock").exists())
    }

    @Test fun manifestFixedRoleReadsOnlyManifest() {
        val dir = root()
        val raw = bytes(23)
        val stageDir = stage(dir)
        File(stageDir, "manifest.bin").writeBytes(raw)
        File(stageDir, "snapshot.bin").writeBytes(bytes(19))
        assertEquals(Verifier.Assessment.ObservedUntrusted(UntrustedStageByteObservation(
            operationId, Verifier.Role.MANIFEST, 23L, sha(raw),
        )), inspect(dir, expected(raw, Verifier.Role.MANIFEST)))
    }

    @Test fun zeroLengthSyntheticFileIsVerifiedWithoutLoopingForever() {
        val dir = root()
        val raw = byteArrayOf()
        write(dir, raw)
        assertEquals(Verifier.Assessment.ObservedUntrusted(UntrustedStageByteObservation(
            operationId, Verifier.Role.SNAPSHOT, 0L, sha(raw),
        )), inspect(dir, expected(raw)))
    }

    @Test fun sameLengthDifferentBytesAreRejected() {
        val dir = root()
        val good = bytes(32)
        val bad = good.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val file = write(dir, bad)
        rejected(inspect(dir, expected(good)), Verifier.Reason.BYTES_MISMATCH)
        assertArrayEquals(bad, file.readBytes())
    }

    @Test fun shortFileIsRejectedWithoutModification() {
        val dir = root()
        val good = bytes(100)
        val short = good.copyOfRange(0, 99)
        val file = write(dir, short)
        rejected(inspect(dir, expected(good)), Verifier.Reason.BYTES_MISMATCH)
        assertArrayEquals(short, file.readBytes())
    }

    @Test fun longerFileIsRejectedWithoutModification() {
        val dir = root()
        val good = bytes(100)
        val longer = good + byteArrayOf(1)
        val file = write(dir, longer)
        rejected(inspect(dir, expected(good)), Verifier.Reason.BYTES_MISMATCH)
        assertArrayEquals(longer, file.readBytes())
    }

    @Test fun expectedBytesBeyondExplicitPerRoleBudgetAreRejected() {
        val dir = root()
        val raw = bytes(9)
        rejected(inspect(dir, expected(raw), limits(snap = 8)), Verifier.Reason.BUDGET_EXCEEDED)
        assertFalse(File(dir, "export-source-snapshots").exists())
    }

    @Test fun actualFileBeyondBudgetIsRejectedEvenWhenExpectedSizeFits() {
        val dir = root()
        val file = write(dir, bytes(9))
        rejected(inspect(dir, expected(bytes(8)), limits(snap = 8)),
            Verifier.Reason.BUDGET_EXCEEDED)
        assertEquals(9L, file.length())
    }

    @Test fun aggregateStageBudgetAlsoAppliesToSingleFile() {
        val dir = root()
        rejected(inspect(dir, expected(bytes(7)), limits(stage = 6)),
            Verifier.Reason.BUDGET_EXCEEDED)
        assertFalse(File(dir, "export-source-snapshots").exists())
    }

    @Test fun negativeExpectedLengthIsRejectedBeforeFilesystemAccess() {
        val dir = root()
        rejected(inspect(dir, expected(bytes(1)).copy(byteCount = -1L)),
            Verifier.Reason.INVALID_EXPECTATION)
        assertFalse(File(dir, "export-source-snapshots").exists())
    }

    @Test fun noncanonicalExpectedDigestIsRejectedBeforeFilesystemAccess() {
        val dir = root()
        rejected(inspect(dir, expected(bytes(1)).copy(sha256 = "A".repeat(64))),
            Verifier.Reason.INVALID_EXPECTATION)
        assertFalse(File(dir, "export-source-snapshots").exists())
    }

    @Test fun missingRootNeverGetsCreated() {
        val missing = File(temp.root, "not-created")
        rejected(inspect(missing, expected(bytes(1))), Verifier.Reason.UNSAFE_DIRECTORY)
        assertFalse(missing.exists())
    }

    @Test fun missingParentNeverGetsCreated() {
        val dir = root()
        rejected(inspect(dir, expected(bytes(1))), Verifier.Reason.UNSAFE_DIRECTORY)
        assertFalse(File(dir, "export-source-snapshots").exists())
    }

    @Test fun wrongOperationIdCannotReadAnotherOperationStage() {
        val dir = root()
        val raw = bytes(12)
        write(dir, raw)
        val other = UUID.fromString("00000000-0000-4000-8000-000000000003")
        rejected(inspect(dir, expected(raw, id = other)), Verifier.Reason.UNSAFE_DIRECTORY)
        assertArrayEquals(raw, File(dir, "export-source-snapshots/$operationId/snapshot.bin").readBytes())
    }

    @Test fun absentRoleFileFailsClosedWithoutCreatingIt() {
        val dir = root()
        stage(dir)
        rejected(inspect(dir, expected(bytes(3))), Verifier.Reason.MISSING_FILE)
        assertFalse(File(dir, "export-source-snapshots/$operationId/snapshot.bin").exists())
    }

    @Test fun directoryAsPayloadFailsClosed() {
        val dir = root()
        val bad = File(stage(dir), "snapshot.bin")
        assertTrue(bad.mkdir())
        rejected(inspect(dir, expected(bytes(1))), Verifier.Reason.UNSAFE_FILE)
        assertTrue(bad.isDirectory)
    }

    @Test fun symbolicPayloadCannotEscapeStage() {
        val dir = root()
        val stageDir = stage(dir)
        val external = File(dir, "external-sentinel").also { it.writeText("never open or change") }
        link(File(stageDir, "snapshot.bin").toPath(), external.toPath())
        rejected(inspect(dir, expected(bytes(1))), Verifier.Reason.UNSAFE_FILE)
        assertEquals("never open or change", external.readText())
    }

    @Test fun symbolicStageCannotEscapeFixture() {
        val dir = root()
        val external = File(dir, "external-dir").also { assertTrue(it.mkdir()) }
        val parent = File(dir, "export-source-snapshots").also { assertTrue(it.mkdir()) }
        link(File(parent, operationId.toString()).toPath(), external.toPath())
        rejected(inspect(dir, expected(bytes(1))), Verifier.Reason.UNSAFE_DIRECTORY)
        assertTrue(external.isDirectory)
    }

    @Test fun symbolicRootIsRejected() {
        val real = root()
        val alias = File(temp.root, "root-alias")
        link(alias.toPath(), real.toPath())
        rejected(inspect(alias, expected(bytes(1))), Verifier.Reason.UNSAFE_DIRECTORY)
        assertFalse(File(real, "export-source-snapshots").exists())
    }

    @Test fun injectedReadFailureIsFailClosedAndPreservesBytes() {
        val dir = root()
        val raw = bytes(8_193)
        val file = write(dir, raw)
        rejected(inspect(dir, expected(raw), after = {
            throw IOException("synthetic injected read fault")
        }), Verifier.Reason.READ_FAILED)
        assertArrayEquals(raw, file.readBytes())
    }

    @Test fun alreadyCancelledJobCannotObserveAnyBytes() {
        val dir = root()
        val raw = bytes(8_193)
        write(dir, raw)
        val cancelled = Job().apply { cancel() }
        val result = runCatching {
            runBlocking(cancelled) { Verifier(dir, limits()).inspect(expected(raw)) }
        }.exceptionOrNull()
        assertTrue("Expected cancellation: $result", result is CancellationException)
    }

    @Test fun cancellationAfterFirstChunkDoesNotReturnPartialSuccess() {
        val dir = root()
        val raw = bytes(8_193)
        val file = write(dir, raw)
        val job = Job()
        var chunks = 0
        val result = runCatching {
            runBlocking(job) { Verifier(dir, limits(), afterChunkRead = {
                chunks++
                job.cancel()
            }).inspect(expected(raw)) }
        }.exceptionOrNull()
        assertTrue("Expected cancellation: $result", result is CancellationException)
        assertEquals(1, chunks)
        assertArrayEquals(raw, file.readBytes())
    }

    @Test fun repeatedReadDoesNotAcquireLockOrChangeStage() {
        val dir = root()
        val raw = bytes(23)
        val file = write(dir, raw)
        val first = inspect(dir, expected(raw))
        val second = inspect(dir, expected(raw))
        assertEquals(first, second)
        assertArrayEquals(raw, file.readBytes())
        assertFalse(File(dir, "seal").exists())
        assertFalse(File(dir, "assets").exists())
    }
}
