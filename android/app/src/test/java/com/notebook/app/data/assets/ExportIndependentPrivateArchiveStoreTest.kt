package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Isolated app-private layout, no Room, managed originals, external destinations or GC. */
class ExportIndependentPrivateArchiveStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val limits = ExportPrivateStageLimits(4, 4096, 8192, 4096, 8)

    private fun id(): String = UUID.randomUUID().toString()
    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }

    private data class Fixture(
        val root: File,
        val draft: ExportPrivatePortableDraftWriter.DraftUntrusted,
        val bytes: ByteArray,
    ) {
        fun retained() = File(root, "retained-private-export-archives/" + draft.operationId + ".notebook")
        fun partial() = File(root, "retained-private-export-archives/" + draft.operationId + ".copying")
        fun source() = File(root, "pending-export-copies/" + draft.operationId + "/draft.notebook")
    }

    private fun fixture(): Fixture {
        val root = temp.newFolder()
        val op = id()
        val note = id()
        val pending = File(root, "pending-export-copies/$op")
        assertTrue(pending.mkdirs())
        val bytes = ByteArray(19_000) { (it % 251).toByte() }
        File(pending, "draft.notebook").writeBytes(bytes)
        return Fixture(root, ExportPrivatePortableDraftWriter.DraftUntrusted(
            op, note, 3L, "draft.notebook", bytes.size.toLong(), sha(bytes),
        ), bytes)
    }

    private fun store(
        root: File,
        sync: (File) -> Unit = {},
        onChunk: (Long) -> Unit = {},
    ) = ExportIndependentPrivateArchiveStore(root, limits.copy(
        maxTotalAssetBytes = 64_000,
    ), syncDirectory = sync, afterWrittenChunk = onChunk)

    @Test fun independentlyCopiesAndRechecksExactlyWithoutConsumingDraft() = runBlocking {
        val f = fixture()
        val observed = store(f.root).copyNew(f.draft)
        assertEquals(f.draft.operationId, observed.operationId)
        assertEquals(f.draft.sha256, observed.sha256)
        assertEquals(f.bytes.size.toLong(), observed.bytes)
        assertEquals(f.bytes.toList(), f.retained().readBytes().toList())
        assertEquals(f.bytes.toList(), f.source().readBytes().toList())
        assertEquals(observed, store(f.root).inspectReadOnly(f.draft))
        assertFalse(f.partial().exists())
        assertFalse(File(f.root, "assets").exists())
    }

    @Test fun existingFinalCannotBeOverwrittenOrAdoptedAsNewSuccess() = runBlocking {
        val f = fixture()
        val first = store(f.root).copyNew(f.draft)
        assertTrue(runCatching {
            store(f.root).copyNew(f.draft)
        }.exceptionOrNull() is IOException)
        assertEquals(first, store(f.root).inspectReadOnly(f.draft))
        assertArrayEquals(f.bytes, f.retained().readBytes())
    }

    @Test fun mismatchedSourceIsRejectedBeforeCreatingRetainedCopy() = runBlocking {
        val f = fixture()
        f.source().writeBytes(f.bytes.reversedArray())
        assertTrue(runCatching {
            store(f.root).copyNew(f.draft)
        }.exceptionOrNull() is IOException)
        assertFalse(f.retained().exists())
        assertFalse(f.partial().exists())
    }

    @Test fun injectedCopyFailureLeavesPartialAndRefusesRetry() = runBlocking {
        val f = fixture()
        val faulted = store(f.root, onChunk = {
            if (it >= 8192L) throw IOException("Simulated interrupted private archive copy")
        })
        assertTrue(runCatching { faulted.copyNew(f.draft) }.exceptionOrNull() is IOException)
        assertTrue(f.partial().exists())
        assertFalse(f.retained().exists())
        assertTrue(runCatching {
            store(f.root).copyNew(f.draft)
        }.exceptionOrNull() is IOException)
        assertArrayEquals(f.bytes, f.source().readBytes())
    }

    @Test fun directorySyncFailureLeavesUntrustedFinalButNoCompletionResult() = runBlocking {
        val f = fixture()
        assertTrue(runCatching {
            store(f.root, sync = {
                if (it.name == "retained-private-export-archives") {
                    throw IOException("Simulated directory fsync failure")
                }
            }).copyNew(f.draft)
        }.exceptionOrNull() is IOException)
        assertFalse(f.retained().exists())
        assertTrue(f.partial().exists())
        assertArrayEquals(f.bytes, f.source().readBytes())
    }

    @Test fun tamperedRetainedCopyFailsReadOnlyRecovery() = runBlocking {
        val f = fixture()
        store(f.root).copyNew(f.draft)
        f.retained().writeBytes(f.bytes.reversedArray())
        assertNull(store(f.root).inspectReadOnly(f.draft))
        assertArrayEquals(f.bytes, f.source().readBytes())
    }

    @Test fun unexpectedFileTypesAndInvalidOperationIdsFailClosed() = runBlocking {
        val f = fixture()
        assertTrue(runCatching {
            store(f.root).copyNew(f.draft.copy(operationId = "../wrong"))
        }.exceptionOrNull() is IOException)
        assertTrue(runCatching {
            store(f.root).copyNew(f.draft.copy(fileName = "other.notebook"))
        }.exceptionOrNull() is IOException)
        assertFalse(f.retained().exists())
    }
}
