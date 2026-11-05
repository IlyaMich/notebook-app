package com.notebook.app.data.assets

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual separate-Android-process lock tests. Run ONLY on the approved disposable AVD.
 * Both processes use a new, UUID-named directory under this app's private filesDir.
 * No notes, Room rows, production assets or external PIDs are accessed.
 */
@RunWith(AndroidJUnit4::class)
class AssetAcquisitionLeaseCrossProcessAndroidTest {
    private lateinit var context: Context
    private lateinit var sandbox: File
    private var bound: BoundProbe? = null
    private var remoteHeldToken: String? = null

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        sandbox = File(context.filesDir, "sto08-lease-xproc-${UUID.randomUUID()}")
        assertFalse("Probe root unexpectedly exists", sandbox.exists())
        assertTrue("Failed to create disposable probe root", sandbox.mkdir())
    }

    @After fun tearDown() = runBlocking {
        val probe = bound
        val token = remoteHeldToken
        if (probe != null && token != null) {
            // Best effort; never assume failure to acknowledge means the lock was released.
            try {
                probe.send(AssetLeaseCrossProcessProbeService.RELEASE, token)
                probe.expect(AssetLeaseCrossProcessProbeService.RELEASED, token, 3_000L)
                remoteHeldToken = null
            } catch (_: Exception) {
                // Leave the disposable directory intact if ownership is not resolved.
            }
        }
        probe?.close()
        bound = null
        if (remoteHeldToken == null) {
            assertTrue("Could not clean isolated probe directory", sandbox.deleteRecursively())
        }
    }

    private fun coordinator(timeout: Long = 2_000L) = AssetAcquisitionLeaseCoordinator(
        sandbox, acquisitionTimeoutMillis = timeout, retryDelayMillis = 10L,
    )

    private suspend fun connected(): BoundProbe {
        val probe = BoundProbe(context)
        bound = probe
        probe.connect()
        return probe
    }

    private suspend fun remotelyHold(probe: BoundProbe, token: String): Int {
        // Treat even an in-flight HOLD as potentially owning the lock during cleanup.
        remoteHeldToken = token
        probe.send(AssetLeaseCrossProcessProbeService.HOLD, token, sandbox.name)
        val started = probe.expect(AssetLeaseCrossProcessProbeService.STARTED, token)
        assertNotEquals("Probe service must be a separate process", Process.myPid(), started.pid)
        val acquired = probe.expect(AssetLeaseCrossProcessProbeService.ACQUIRED, token)
        assertEquals(started.pid, acquired.pid)
        remoteHeldToken = token
        return acquired.pid
    }

    private suspend fun remotelyRelease(probe: BoundProbe, token: String) {
        probe.send(AssetLeaseCrossProcessProbeService.RELEASE, token)
        probe.expect(AssetLeaseCrossProcessProbeService.RELEASED, token)
        remoteHeldToken = null
    }

    @Test fun remoteLeaseBlocksLocalAcquisitionUntilExplicitRelease() = runBlocking {
        val probe = connected()
        val token = UUID.randomUUID().toString()
        remotelyHold(probe, token)
        val failure = runCatching { coordinator(175L).withLease { "should-not-run" } }
            .exceptionOrNull()
        assertTrue("Expected lock contention timeout, got $failure", failure is TimeoutCancellationException)
        remotelyRelease(probe, token)
        assertEquals("available", withTimeout(5_000L) { coordinator().withLease { "available" } })
    }

    @Test fun localLeaseBlocksRemoteAcquisitionUntilLocalRelease() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) {
            coordinator().withLease {
                entered.complete(Unit)
                release.await()
            }
        }
        try {
            withTimeout(5_000L) { entered.await() }
            val probe = connected()
            val token = UUID.randomUUID().toString()
            remoteHeldToken = token
            probe.send(AssetLeaseCrossProcessProbeService.HOLD, token, sandbox.name)
            val started = probe.expect(AssetLeaseCrossProcessProbeService.STARTED, token)
            assertNotEquals(Process.myPid(), started.pid)
            assertNull("Remote acquired while local lease held", withTimeoutOrNull(250L) {
                probe.next()
            })
            release.complete(Unit)
            withTimeout(5_000L) { holder.await() }
            val acquired = probe.expect(AssetLeaseCrossProcessProbeService.ACQUIRED, token)
            assertEquals(started.pid, acquired.pid)
            remoteHeldToken = token
            remotelyRelease(probe, token)
        } finally {
            release.complete(Unit)
            holder.cancelAndJoin()
        }
    }

    @Test fun remoteProcessDeathReleasesNativeLock() = runBlocking {
        val probe = connected()
        val token = UUID.randomUUID().toString()
        remotelyHold(probe, token)
        val death = CompletableDeferred<Unit>()
        val recipient = IBinder.DeathRecipient { death.complete(Unit) }
        probe.binder.linkToDeath(recipient, 0)
        try {
            probe.send(AssetLeaseCrossProcessProbeService.DIE, token)
            withTimeout(8_000L) { death.await() }
            // Binder death confirms only the probe process was terminated.
            remoteHeldToken = null
            assertEquals("recovered", withTimeout(5_000L) {
                coordinator().withLease { "recovered" }
            })
        } finally {
            probe.binder.unlinkToDeath(recipient, 0)
        }
    }

    private data class Reply(val kind: Int, val pid: Int, val token: String)

    private class BoundProbe(private val context: Context) {
        private val connected = CompletableDeferred<IBinder>()
        private val events = Channel<Reply>(Channel.UNLIMITED)
        private val receiving = Messenger(Handler(Looper.getMainLooper()) { message ->
            events.trySend(Reply(
                message.what,
                message.arg1,
                message.data.getString(AssetLeaseCrossProcessProbeService.KEY_TOKEN).orEmpty(),
            ))
            true
        })
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (!connected.isCompleted) {
                    connected.completeExceptionally(IllegalStateException("Probe process disconnected"))
                }
            }
            override fun onNullBinding(name: ComponentName) {
                if (!connected.isCompleted) {
                    connected.completeExceptionally(IllegalStateException("Probe service returned null"))
                }
            }
        }
        private var didBind = false
        lateinit var binder: IBinder
            private set
        private lateinit var remote: Messenger

        suspend fun connect() {
            val intent = Intent().setClassName(
                context.packageName,
                "com.notebook.app.data.assets.AssetLeaseCrossProcessProbeService",
            )
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                throw IllegalStateException("Could not bind debug-only remote lease probe")
            }
            didBind = true
            binder = withTimeout(7_000L) { connected.await() }
            remote = Messenger(binder)
        }

        fun send(kind: Int, token: String, rootName: String? = null) {
            val message = Message.obtain(null, kind)
            message.replyTo = receiving
            message.data = Bundle().apply {
                putString(AssetLeaseCrossProcessProbeService.KEY_TOKEN, token)
                if (rootName != null) {
                    putString(AssetLeaseCrossProcessProbeService.KEY_ROOT_NAME, rootName)
                }
            }
            remote.send(message)
        }

        suspend fun next(): Reply = events.receive()

        suspend fun expect(kind: Int, token: String, timeout: Long = 7_000L): Reply {
            val reply = withTimeout(timeout) { next() }
            assertEquals("Wrong IPC token", token, reply.token)
            assertEquals("Unexpected IPC reply type (ERROR=14)", kind, reply.kind)
            return reply
        }

        fun close() {
            if (didBind) {
                context.unbindService(connection)
                didBind = false
            }
            events.close()
        }
    }
}
