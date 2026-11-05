package com.notebook.app.data.assets

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssetAcquisitionLeaseCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()

    private fun coordinator(timeout: Long = 2_000L) = AssetAcquisitionLeaseCoordinator(
        temp.root, acquisitionTimeoutMillis = timeout, retryDelayMillis = 10L,
    )

    @Test fun leaseRunsSuspendingActionAndCreatesNoAssetContent() = runBlocking {
        val result = coordinator().withLease {
            withTimeout(2_000L) { kotlinx.coroutines.delay(10L) }
            "completed"
        }
        assertEquals("completed", result)
        assertTrue(File(temp.root, ".asset-publication.lock").isFile)
        assertFalse(File(temp.root, "assets").exists())
        assertFalse(File(temp.root, "staging").exists())
    }

    @Test fun separateCoordinatorsSerializeInSameJvm() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            coordinator().withLease { entered.complete(Unit); release.await(); "first" }
        }
        try {
            withTimeout(3_000L) { entered.await() }
            val second = async(Dispatchers.Default) {
                coordinator().withLease { secondEntered.complete(Unit); "second" }
            }
            try {
                assertNull(withTimeoutOrNull(150L) { secondEntered.await(); true })
                release.complete(Unit)
                assertEquals("first", withTimeout(3_000L) { first.await() })
                assertEquals("second", withTimeout(3_000L) { second.await() })
            } finally {
                second.cancelAndJoin()
            }
        } finally {
            release.complete(Unit)
            first.cancelAndJoin()
        }
    }

    @Test fun acquisitionTimeoutFailsClosedAndReleasesChannel() = runBlocking {
        val lockPath = File(temp.root, ".asset-publication.lock").toPath()
        FileChannel.open(lockPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { channel ->
            channel.lock().use {
                val failure = runCatching { coordinator(timeout = 120L).withLease { "unexpected" } }
                    .exceptionOrNull()
                assertTrue("Expected acquisition timeout, got $failure", failure is TimeoutCancellationException)
            }
        }
        assertEquals("available", coordinator().withLease { "available" })
    }

    @Test fun cancellationWhileWaitingDoesNotKeepTheLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) {
            coordinator().withLease { entered.complete(Unit); release.await() }
        }
        try {
            withTimeout(3_000L) { entered.await() }
            val waiterEntered = CompletableDeferred<Unit>()
            val waiting = async(Dispatchers.Default) {
                coordinator(timeout = 4_000L).withLease { waiterEntered.complete(Unit) }
            }
            assertNull(withTimeoutOrNull(150L) { waiterEntered.await(); true })
            waiting.cancelAndJoin()
            assertTrue(waiting.isCancelled)
            release.complete(Unit)
            withTimeout(3_000L) { holder.await() }
            assertEquals(42, withTimeout(3_000L) { coordinator().withLease { 42 } })
        } finally {
            release.complete(Unit)
            holder.cancelAndJoin()
        }
    }

    @Test fun cancellationInsideSuspendedBodyReleasesLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            coordinator().withLease { entered.complete(Unit); awaitCancellation() }
        }
        try {
            withTimeout(3_000L) { entered.await() }
        } finally {
            job.cancelAndJoin()
        }
        assertTrue(job.isCancelled)
        assertEquals("released", withTimeout(3_000L) { coordinator().withLease { "released" } })
    }

    @Test fun thrownBodyReleasesLockWithoutSuppressingFailure() = runBlocking {
        val failure = runCatching {
            coordinator().withLease {
                throw IllegalStateException("expected body failure")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("expected body failure", failure?.message)
        assertEquals("released", coordinator().withLease { "released" })
    }

    @Test fun existingPublisherHoldsSameLockUntilPublicationFinishes() = runBlocking {
        val publisherLocked = CountDownLatch(1)
        val allowPublisher = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x00,
        )
        try {
            val publisher = executor.submit<ManagedAssetFileStore.FinalizedFile> {
                ManagedAssetFileStore(
                    temp.root,
                    directorySync = {},
                    afterPublishBytesWritten = {
                        publisherLocked.countDown()
                        if (!allowPublisher.await(5, TimeUnit.SECONDS)) {
                            throw IOException("Publisher test gate timed out")
                        }
                    },
                ).stageAndFinalize(ByteArrayInputStream(png), "image/png")
            }
            assertTrue("Publisher did not reach lock-protected copy", publisherLocked.await(5, TimeUnit.SECONDS))
            val acquired = CompletableDeferred<Unit>()
            val waiter = async(Dispatchers.Default) {
                coordinator(timeout = 4_000L).withLease { acquired.complete(Unit) }
            }
            try {
                assertNull(withTimeoutOrNull(150L) { acquired.await(); true })
                allowPublisher.countDown()
                assertNotNull(publisher.get(5, TimeUnit.SECONDS))
                withTimeout(3_000L) { waiter.await() }
                assertTrue(acquired.isCompleted)
            } finally {
                allowPublisher.countDown()
                waiter.cancelAndJoin()
            }
        } finally {
            allowPublisher.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun nestedSynchronousPublicationIsRejectedWithoutAcknowledgement() = runBlocking {
        // The synchronous publisher's FileChannel.lock is non-reentrant with this lease.
        // A future attachment caller MUST register its asset BEFORE obtaining the lease.
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x00,
        )
        val failure = coordinator().withLease {
            runCatching {
                ManagedAssetFileStore(temp.root, directorySync = {}).stageAndFinalize(
                    ByteArrayInputStream(png), "image/png",
                )
            }.exceptionOrNull()
        }
        assertTrue("Nested publisher must fail closed, got $failure",
            failure is java.nio.channels.OverlappingFileLockException)
        assertFalse(File(temp.root, "assets").exists())
    }

    @Test fun invalidPrivateRootRejectsWithoutCreatingDirectory() = runBlocking {
        val root = File(temp.root, "does-not-exist")
        val failure = runCatching {
            AssetAcquisitionLeaseCoordinator(root).withLease { "unexpected" }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(root.exists())
    }
}
