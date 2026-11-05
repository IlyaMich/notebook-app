package com.notebook.app.data.assets

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * STO-08 C2C-2C debug-only test fixture. This bound service runs in an explicitly
 * separate Android process and accesses ONLY a disposable, UUID-named test root.
 * Its only death command terminates its OWN process (never another PID).
 * Do not register this service in the release/main manifest.
 */
internal class AssetLeaseCrossProcessProbeService : Service() {
    companion object {
        const val HOLD = 1
        const val RELEASE = 2
        const val DIE = 3
        const val STARTED = 11
        const val ACQUIRED = 12
        const val RELEASED = 13
        const val ERROR = 14
        const val KEY_TOKEN = "token"
        const val KEY_ROOT_NAME = "root_name"

        private val SANDBOX_NAME = Regex(
            "sto08-lease-xproc-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        )
        private val TOKEN = Regex("[0-9a-f-]{36}")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeToken: String? = null // Accessed on main Looper only.
    private var releaseGate: CompletableDeferred<Unit>? = null // Accessed on main Looper only.
    @Volatile private var acquired = false

    private val handler = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            HOLD -> begin(message)
            RELEASE -> release(message)
            DIE -> die(message)
        }
        true
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        releaseGate?.complete(Unit)
        scope.cancel()
        super.onDestroy()
    }

    private fun begin(message: Message) {
        val client = message.replyTo ?: return
        val token = message.data.getString(KEY_TOKEN).orEmpty()
        val rootName = message.data.getString(KEY_ROOT_NAME).orEmpty()
        val root = File(filesDir, rootName)
        if (activeToken != null || !TOKEN.matches(token) ||
            !SANDBOX_NAME.matches(rootName) ||
            !Files.isDirectory(filesDir.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            !Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            respond(client, ERROR, token)
            return
        }

        val gate = CompletableDeferred<Unit>()
        activeToken = token
        releaseGate = gate
        acquired = false
        respond(client, STARTED, token)

        scope.launch {
            try {
                AssetAcquisitionLeaseCoordinator(
                    root, acquisitionTimeoutMillis = 10_000L, retryDelayMillis = 10L,
                ).withLease {
                    acquired = true
                    respond(client, ACQUIRED, token)
                    gate.await()
                }
                withContext(Dispatchers.Main.immediate) {
                    reset()
                    respond(client, RELEASED, token)
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main.immediate) { reset() }
            } catch (_: Exception) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    reset()
                    respond(client, ERROR, token)
                }
            }
        }
    }

    private fun release(message: Message) {
        val token = message.data.getString(KEY_TOKEN).orEmpty()
        if (activeToken != token || releaseGate == null) {
            message.replyTo?.let { respond(it, ERROR, token) }
            return
        }
        releaseGate?.complete(Unit)
    }

    private fun die(message: Message) {
        val token = message.data.getString(KEY_TOKEN).orEmpty()
        if (activeToken != token || !acquired) {
            message.replyTo?.let { respond(it, ERROR, token) }
            return
        }
        // The PID is obtained inside this separate debug-only process, not supplied by IPC.
        Process.killProcess(Process.myPid())
    }

    private fun reset() {
        activeToken = null
        releaseGate = null
        acquired = false
    }

    private fun respond(client: Messenger, kind: Int, token: String) {
        val reply = Message.obtain(null, kind)
        reply.arg1 = Process.myPid()
        reply.data = Bundle().apply { putString(KEY_TOKEN, token) }
        try {
            client.send(reply)
        } catch (_: RemoteException) {
            // Instrumentation client disappeared. Lease lifetime remains governed by scope.
        }
    }
}
