package com.notebook.app.data.assets

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.launch

/**
 * DEBUG APK ONLY: opt-in manual picker grant and process-death probe.
 *
 * NEVER uses library.sqlite, any managed-original path, user note/attachment,
 * production export holds, or a raw persisted URI. Only writes an explicitly
 * user-selected NEW document on an approved disposable Android emulator.
 *
 * A successful same-session readback and a debug SharedPreferences marker are
 * NOT a completion receipt or remote-provider crash durability proof.
 */
internal class Sto08D2iManualSafProbeActivity : ComponentActivity() {
    private enum class Mode { SUCCESS, KILL_DURING_PROVIDER_WRITE }
    private var selectedMode: Mode? = null
    private lateinit var status: TextView
    private lateinit var successButton: Button
    private lateinit var killButton: Button

    private val picker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-notebook"),
    ) { uri ->
        val requested = selectedMode
        selectedMode = null
        if (requested == null) {
            show("No current user-initiated probe; returned URI ignored.")
        } else if (uri == null) {
            show("Picker cancelled. No external write or attempt journal created.")
        } else {
            onFreshSelectedUri(requested, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedMode = when (savedInstanceState?.getString("pendingMode")) {
            Mode.SUCCESS.name -> Mode.SUCCESS
            Mode.KILL_DURING_PROVIDER_WRITE.name -> Mode.KILL_DURING_PROVIDER_WRITE
            else -> null
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }
        status = TextView(this).apply { textSize = 16f }
        successButton = Button(this).apply {
            text = "1. Create ONE disposable SAF document; verify readback"
            setOnClickListener { startPicker(Mode.SUCCESS) }
        }
        killButton = Button(this).apply {
            text = "2. Create ONE disposable SAF document; KILL app mid-write"
            setOnClickListener { startPicker(Mode.KILL_DURING_PROVIDER_WRITE) }
        }
        page.addView(status, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        page.addView(successButton)
        page.addView(killButton)
        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)
        show("Manual test fixture. Never choose a personal or cloud document.")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingMode", selectedMode?.name)
        super.onSaveInstanceState(outState)
    }

    private fun ledger(mode: Mode): Sto08D2iManualSafProbeJournal =
        Sto08D2iManualSafProbeJournal(
            this, if (mode == Mode.SUCCESS)
                Sto08D2iManualSafProbeJournal.SUCCESS_LEDGER
            else Sto08D2iManualSafProbeJournal.DEATH_LEDGER,
        )

    private fun emulatorOnly(): Boolean {
        val hardware = Build.HARDWARE.lowercase()
        return hardware.contains("ranchu") || hardware.contains("goldfish")
    }

    private fun show(message: String) {
        val success = ledger(Mode.SUCCESS).inspect()
        val death = ledger(Mode.KILL_DURING_PROVIDER_WRITE).inspect()
        status.text = """
            STO-08 D2I: DEBUG-ONLY, disposable emulator manual probe

            Creates exactly one new user-selected file per mode, with generated
            nonpersonal ZIP bytes. Choose ONLY Android emulator Downloads/internal
            storage in the system picker. Do not choose any real or cloud files.

            The KILL button will deliberately terminate this app's process
            after the first provider write chunk. It does NOT touch originals,
            library.sqlite or production export holds. Reopen this probe after
            the process dies. No retry and no external file cleanup happen here.

            Success ledger: $success
            Process-death ledger: $death

            Current result: $message

            No stored URI, no completion receipt, no hold release, no GC.
        """.trimIndent()
        successButton.isEnabled =
            emulatorOnly() && success is Sto08D2iManualSafProbeJournal.Status.NeverAttempted
        killButton.isEnabled =
            emulatorOnly() && death is Sto08D2iManualSafProbeJournal.Status.NeverAttempted
    }

    private fun startPicker(mode: Mode) {
        if (!emulatorOnly() ||
            ledger(mode).inspect() !is Sto08D2iManualSafProbeJournal.Status.NeverAttempted ||
            selectedMode != null
        ) {
            show("Blocked: no emulator, existing attempt, or picker already running.")
            return
        }
        selectedMode = mode
        picker.launch("sto08-d2i-disposable-" + mode.name.lowercase() + "-" +
            UUID.randomUUID() + ".notebook")
    }

    private fun onFreshSelectedUri(mode: Mode, uri: Uri) {
        if (!emulatorOnly() ||
            ledger(mode).inspect() !is Sto08D2iManualSafProbeJournal.Status.NeverAttempted ||
            uri.scheme != "content" ||
            !DocumentsContract.isDocumentUri(this, uri) ||
            uri.authority !in setOf(
                "com.android.externalstorage.documents",
                "com.android.providers.downloads.documents",
            )
        ) {
            // Picker may already have created a document. Never delete it or
            // write to it; human tester can remove this disposable file.
            show("Blocked URI/provider. No bytes written. Remove selected empty " +
                "disposable file manually if the picker created it.")
            return
        }
        lifecycleScope.launch {
            try {
                // Only app-private generated test bytes; never a real export.
                val root = File(filesDir, "sto08_d2i_manual_picker_probe")
                val fixture = generateDisposableArchive(root)
                val destination = ExportSafCreatedDocumentDestination(
                    this@Sto08D2iManualSafProbeActivity, uri,
                )
                val oneShot = ledger(mode)
                val admitted = oneShot.beginOnce(
                    Sto08D2iManualSafProbeJournal.Attempt(
                        fixture.operationId, fixture.sha256, fixture.bytes,
                        destination.destinationIdentitySha256,
                    ),
                )
                if (!admitted) throw IOException("Manual probe already attempted or ambiguous")
                show("Attempt committed. Provider may now have partial bytes; " +
                    "no automatic retry is permitted.")
                val limits = ExportPrivateStageLimits(4, 100_000, 400_000, 100_000, 8)
                val handoff = ExportExternalDocumentByteHandoff(
                    root, limits,
                    afterCopiedChunk = { count ->
                        if (mode == Mode.KILL_DURING_PROVIDER_WRITE && count > 0L) {
                            // Deliberate process kill ONLY after explicit KILL
                            // button plus user-approved CREATE_DOCUMENT return.
                            Process.killProcess(Process.myPid())
                            throw IOException("Process death was not delivered")
                        }
                    },
                )
                val observed = handoff.copyAndReread(fixture, destination)
                if (!oneShot.recordReadbackOnce(
                        observed.operationId, observed.sha256, observed.byteCount,
                    )
                ) throw IOException("Debug-only observation could not be recorded")
                show("Matching bytes ONCE observed. Still untrusted; provider " +
                    "durability/atomicity and any release remain unproven.")
            } catch (_: Exception) {
                show("Incomplete/ambiguous. Do not reuse this attempt, delete a " +
                    "provider document automatically, or infer crash durability.")
            }
        }
    }

    private fun generateDisposableArchive(
        root: File,
    ): ExportIndependentPrivateArchiveStore.CopyObservedUntrusted {
        if (!root.isDirectory && !root.mkdir()) {
            throw IOException("Could not create isolated manual-probe root")
        }
        val dir = File(root, "retained-private-export-archives")
        if (!dir.isDirectory && !dir.mkdir()) {
            throw IOException("Could not create isolated retained test fixture root")
        }
        val operation = UUID.randomUUID().toString()
        val file = File(dir, operation + ".notebook")
        if (!file.createNewFile()) throw IOException("Test fixture already exists")
        val randomBytes = ByteArray(96 * 1024)
        SecureRandom().nextBytes(randomBytes)
        FileOutputStream(file).use { out ->
            val zip = ZipOutputStream(out)
            zip.putNextEntry(ZipEntry("STO08_D2I_DISPOSABLE_TEST_BYTES_ONLY.bin"))
            zip.write(randomBytes)
            zip.closeEntry()
            zip.finish()
            zip.flush()
            out.fd.sync()
            zip.close()
        }
        randomBytes.fill(0)
        val bytes = file.readBytes()
        if (bytes.size.toLong() !in 1L..Sto08D2iManualSafProbeJournal.MAX_PROBE_BYTES) {
            throw IOException("Debug probe archive exceeds strict test-only budget")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return ExportIndependentPrivateArchiveStore.CopyObservedUntrusted(
            operationId = operation,
            privateFileName = file.name,
            bytes = bytes.size.toLong(),
            sha256 = digest,
        )
    }
}
