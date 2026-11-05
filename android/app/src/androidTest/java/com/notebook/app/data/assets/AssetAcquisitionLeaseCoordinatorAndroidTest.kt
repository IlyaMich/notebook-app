package com.notebook.app.data.assets

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 C2C-2C: real Android filesystem and coroutine checks for the shared lock domain.
 * The sandbox is a fresh UUID-named directory; no Room, notes, user originals or app assets
 * outside that directory are accessed. This does NOT prove cross-process/death semantics.
 */
@RunWith(AndroidJUnit4::class)
class AssetAcquisitionLeaseCoordinatorAndroidTest {
    private lateinit var sandbox: File

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        sandbox = File(context.filesDir, "sto08-lease-probe-${UUID.randomUUID()}")
        assertFalse("Disposable test root already exists", sandbox.exists())
        assertTrue("Unable to create disposable test root", sandbox.mkdir())
    }

    @After fun tearDown() {
        if (::sandbox.isInitialized && sandbox.name.startsWith("sto08-lease-probe-")) {
            assertTrue("Could not remove disposable test root", sandbox.deleteRecursively())
        }
    }

    private fun coordinator(timeout: Long = 3_000L) = AssetAcquisitionLeaseCoordinator(
        sandbox, acquisitionTimeoutMillis = timeout, retryDelayMillis = 10L,
    )

    @Test fun suspendingBodyRunsWithoutCreatingAnyAssetOrDatabase() = runBlocking {
        val result = coordinator().withLease {
            delay(25L)
            "completed"
        }
        assertEquals("completed", result)
        assertTrue(File(sandbox, ".asset-publication.lock").isFile)
        assertFalse(File(sandbox, "assets").exists())
        assertFalse(File(sandbox, "staging").exists())
    }

    @Test fun twoInstancesSerializeAcrossSuspension() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) {
            coordinator().withLease { entered.complete(Unit); release.await() }
        }
        try {
            withTimeout(5_000L) { entered.await() }
            val waiter = async(Dispatchers.Default) {
                coordinator().withLease { secondEntered.complete(Unit) }
            }
            try {
                assertNull(withTimeoutOrNull(175L) { secondEntered.await(); true })
                release.complete(Unit)
                withTimeout(5_000L) { holder.await() }
                withTimeout(5_000L) { waiter.await() }
                assertTrue(secondEntered.isCompleted)
            } finally {
                waiter.cancelAndJoin()
            }
        } finally {
            release.complete(Unit)
            holder.cancelAndJoin()
        }
    }

    @Test fun existingFileChannelLockTimesOutThenReleasesCleanly() = runBlocking {
        val lockPath = File(sandbox, ".asset-publication.lock").toPath()
        FileChannel.open(lockPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { channel ->
            channel.lock().use {
                val failure = runCatching {
                    coordinator(timeout = 150L).withLease { "must-not-run" }
                }.exceptionOrNull()
                assertTrue("Expected acquisition timeout, got $failure", failure is TimeoutCancellationException)
            }
        }
        assertEquals("available", withTimeout(5_000L) { coordinator().withLease { "available" } })
    }

    @Test fun cancellationWhileActionSuspendsReleasesNativeLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) {
            coordinator().withLease { entered.complete(Unit); awaitCancellation() }
        }
        try {
            withTimeout(5_000L) { entered.await() }
        } finally {
            holder.cancelAndJoin()
        }
        assertTrue(holder.isCancelled)
        assertEquals(42, withTimeout(5_000L) { coordinator().withLease { 42 } })
    }

    @Test fun synchronousFilePublisherAndLeaseExcludeEachOther() = runBlocking {
        val publisherReachedCopy = CountDownLatch(1)
        val releasePublisher = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x00,
        )
        try {
            val publisher = executor.submit<ManagedAssetFileStore.FinalizedFile> {
                ManagedAssetFileStore(
                    sandbox,
                    afterPublishBytesWritten = {
                        publisherReachedCopy.countDown()
                        if (!releasePublisher.await(8, TimeUnit.SECONDS)) {
                            throw IOException("Timed out holding publisher lock for test")
                        }
                    },
                ).stageAndFinalize(ByteArrayInputStream(pngHeader), "image/png")
            }
            assertTrue("Publisher never acquired its lock", publisherReachedCopy.await(5, TimeUnit.SECONDS))
            val acquired = CompletableDeferred<Unit>()
            val waiter = async(Dispatchers.Default) {
                coordinator(timeout = 5_000L).withLease { acquired.complete(Unit) }
            }
            try {
                assertNull(withTimeoutOrNull(175L) { acquired.await(); true })
                releasePublisher.countDown()
                publisher.get(8, TimeUnit.SECONDS)
                withTimeout(5_000L) { waiter.await() }
                assertTrue(acquired.isCompleted)
            } finally {
                releasePublisher.countDown()
                waiter.cancelAndJoin()
            }
        } finally {
            releasePublisher.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun symlinkLockFileIsRejectedWithoutTouchingTarget() = runBlocking {
        val target = File(sandbox, "protected-target")
        val content = byteArrayOf(1, 2, 3, 4)
        target.writeBytes(content)
        val link = File(sandbox, ".asset-publication.lock")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val failure = runCatching { coordinator().withLease { fail("Linked lock must not be accepted") } }
            .exceptionOrNull()
        assertTrue("Expected IOException for lock-file symlink, got $failure", failure is IOException)
        assertTrue(content.contentEquals(target.readBytes()))
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertFalse(File(sandbox, "assets").exists())
    }
}
