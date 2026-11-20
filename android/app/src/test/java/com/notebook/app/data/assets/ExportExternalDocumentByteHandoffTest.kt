package com.notebook.app.data.assets

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pure JVM failure-injection tests: synthetic archive and fake external provider. */
class ExportExternalDocumentByteHandoffTest {
    @get:Rule val temp = TemporaryFolder()
    private val limits = ExportPrivateStageLimits(4, 4096, 64000, 4096, 8)

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class Fixture(
        val root: java.io.File,
        val source: java.io.File,
        val bytes: ByteArray,
        val expected: ExportIndependentPrivateArchiveStore.CopyObservedUntrusted,
    )

    private fun fixture(): Fixture {
        val root = temp.newFolder()
        val retained = java.io.File(root, "retained-private-export-archives")
        assertTrue(retained.mkdir())
        val id = UUID.randomUUID().toString()
        val bytes = ByteArray(19_047) { (it * 7 % 251).toByte() }
        val file = java.io.File(retained, "$id.notebook")
        file.writeBytes(bytes)
        return Fixture(root, file, bytes,
            ExportIndependentPrivateArchiveStore.CopyObservedUntrusted(
                id, "$id.notebook", bytes.size.toLong(), sha(bytes),
            ),
        )
    }

    private class Destination(
        private val readback: (ByteArray) -> ByteArray = { it },
        private val failSync: Boolean = false,
        private val failAtBytes: Int? = null,
    ) : ExportExternalDocumentDestination {
        override val destinationIdentitySha256: String = "1".repeat(64)
        val outputBytes = ByteArrayOutputStream()
        var writes = 0
        var syncs = 0
        var reads = 0

        override fun openWriteOnce(): ExportExternalDocumentDestination.Sink {
            check(writes++ == 0)
            val stream: OutputStream = if (failAtBytes == null) outputBytes else
                object : OutputStream() {
                    override fun write(b: Int) {
                        if (outputBytes.size() >= failAtBytes) {
                            throw IOException("Injected provider write error")
                        }
                        outputBytes.write(b)
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val remaining = failAtBytes - outputBytes.size()
                        if (remaining <= 0) throw IOException("Injected provider write error")
                        val allowed = minOf(len, remaining)
                        outputBytes.write(b, off, allowed)
                        if (allowed != len) throw IOException("Injected partial provider write")
                    }
                }
            return object : ExportExternalDocumentDestination.Sink {
                override val output = stream
                override fun syncOrThrow() {
                    syncs++
                    if (failSync) throw IOException("Injected provider fsync failure")
                }
                override fun close() {
                    output.close()
                }
            }
        }

        override fun openReadBack(): InputStream {
            reads++
            return ByteArrayInputStream(readback(outputBytes.toByteArray()))
        }
    }

    private fun handoff(f: Fixture, onCopy: (Long) -> Unit = {}) =
        ExportExternalDocumentByteHandoff(f.root, limits, afterCopiedChunk = onCopy)

    @Test fun exactExternalReadbackRemainsUntrustedAndPrivateSourceUnchanged() = runBlocking {
        val f = fixture()
        val sink = Destination()
        val result = handoff(f).copyAndReread(f.expected, sink)
        assertEquals(ExportExternalDocumentByteHandoff.RereadObservedUntrusted(
            f.expected.operationId, f.expected.bytes, f.expected.sha256,
        ), result)
        assertArrayEquals(f.bytes, sink.outputBytes.toByteArray())
        assertArrayEquals(f.bytes, f.source.readBytes())
        assertEquals(1, sink.writes)
        assertEquals(1, sink.syncs)
        assertEquals(1, sink.reads)
    }

    @Test fun mismatchedSourceRejectsBeforeOpeningExternalDestination() = runBlocking {
        val f = fixture()
        f.source.writeBytes(f.bytes.reversedArray())
        val sink = Destination()
        assertTrue(runCatching {
            handoff(f).copyAndReread(f.expected, sink)
        }.exceptionOrNull() is IOException)
        assertEquals(0, sink.writes)
        assertEquals(0, sink.reads)
    }

    @Test fun invalidOperationAndArchiveFilenameAreRejectedBeforeWrite() = runBlocking {
        val f = fixture()
        val sink = Destination()
        assertTrue(runCatching {
            handoff(f).copyAndReread(f.expected.copy(operationId = "../unexpected"), sink)
        }.exceptionOrNull() is IOException)
        assertTrue(runCatching {
            handoff(f).copyAndReread(f.expected.copy(privateFileName = "original.png"), sink)
        }.exceptionOrNull() is IOException)
        assertEquals(0, sink.writes)
    }

    @Test fun providerWriteErrorLeavesPartialExternalBytesWithoutDeletingAnything() = runBlocking {
        val f = fixture()
        val sink = Destination(failAtBytes = 9000)
        assertTrue(runCatching {
            handoff(f).copyAndReread(f.expected, sink)
        }.exceptionOrNull() is IOException)
        assertTrue(sink.outputBytes.size() in 1 until f.bytes.size)
        assertEquals(0, sink.reads)
        assertArrayEquals(f.bytes, f.source.readBytes())
    }

    @Test fun unsupportedProviderDataSyncRejectsWithoutReadbackSuccess() = runBlocking {
        val f = fixture()
        val sink = Destination(failSync = true)
        assertTrue(runCatching {
            handoff(f).copyAndReread(f.expected, sink)
        }.exceptionOrNull() is IOException)
        assertEquals(1, sink.writes)
        assertEquals(1, sink.syncs)
        assertEquals(0, sink.reads)
        assertArrayEquals(f.bytes, f.source.readBytes())
    }

    @Test fun mismatchedExternalReadbackNeverPasses() = runBlocking {
        val f = fixture()
        val sink = Destination(readback = { it.reversedArray() })
        assertTrue(runCatching {
            handoff(f).copyAndReread(f.expected, sink)
        }.exceptionOrNull() is IOException)
        assertEquals(1, sink.reads)
        assertArrayEquals(f.bytes, f.source.readBytes())
    }

    @Test fun truncatedExternalReadbackNeverPasses() = runBlocking {
        val f = fixture()
        val sink = Destination(readback = { it.copyOf(it.size - 1) })
        assertTrue(runCatching {
            handoff(f).copyAndReread(f.expected, sink)
        }.exceptionOrNull() is IOException)
        assertEquals(1, sink.reads)
    }

    @Test fun expandedExternalReadbackNeverPasses() = runBlocking {
        val f = fixture()
        val sink = Destination(readback = { it + byteArrayOf(7) })
        assertTrue(runCatching {
            handoff(f).copyAndReread(f.expected, sink)
        }.exceptionOrNull() is IOException)
        assertEquals(1, sink.reads)
    }

    @Test fun sourceMutationDuringProviderHandoffFailsClosed() = runBlocking {
        val f = fixture()
        val sink = Destination()
        var changed = false
        assertTrue(runCatching {
            handoff(f, onCopy = {
                if (!changed) {
                    changed = true
                    f.source.writeBytes(f.bytes.reversedArray())
                }
            }).copyAndReread(f.expected, sink)
        }.exceptionOrNull() is IOException)
        assertTrue(changed)
    }

    @Test fun cancellationDuringProviderWritePropagatesWithoutRetry() = runBlocking {
        val f = fixture()
        val sink = Destination()
        val failure = runCatching {
            handoff(f, onCopy = {
                throw CancellationException("Synthetic cancelled provider handoff")
            }).copyAndReread(f.expected, sink)
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, sink.writes)
        assertEquals(0, sink.reads)
        assertArrayEquals(f.bytes, f.source.readBytes())
    }
}
