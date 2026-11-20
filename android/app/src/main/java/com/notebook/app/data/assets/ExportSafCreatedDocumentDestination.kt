package com.notebook.app.data.assets

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Storage Access Framework adapter for an ACTION_CREATE_DOCUMENT result.
 *
 * Caller MUST obtain a *new*, user-approved document URI from the system picker.
 * An arbitrary persisted/old URI cannot prove fresh creation and may be writable;
 * do not expose this adapter as a generic user-entered-URI writer.
 *
 * A DocumentProvider can be remote, delay writes, lack fsync support or fail
 * after accepting bytes. No provider-independent crash-durable commit protocol
 * exists here. Successful readback remains UNTRUSTED and never releases pins.
 */
internal class ExportSafCreatedDocumentDestination(
    context: Context,
    private val documentUri: Uri,
) : ExportExternalDocumentDestination {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    override val destinationIdentitySha256: String =
        MessageDigest.getInstance("SHA-256").digest(
            documentUri.toString().toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val attempted = AtomicBoolean(false)

    init {
        if (documentUri.scheme != "content" || documentUri.authority.isNullOrBlank() ||
            documentUri.fragment != null ||
            !DocumentsContract.isDocumentUri(app, documentUri) ||
            documentUri.authority == app.packageName ||
            documentUri.authority?.startsWith(app.packageName + ".") == true
        ) throw IllegalArgumentException("Expected an external SAF document URI from CREATE_DOCUMENT")
    }

    override fun openWriteOnce(): ExportExternalDocumentDestination.Sink {
        if (!attempted.compareAndSet(false, true)) {
            throw IOException("External document destination already attempted; never retry")
        }
        val descriptor = try {
            resolver.openFileDescriptor(documentUri, "wt")
                ?: throw IOException("External document did not provide a write descriptor")
        } catch (error: SecurityException) {
            throw IOException("No write permission for selected document", error)
        }
        val stream = try {
            FileOutputStream(descriptor.fileDescriptor)
        } catch (failure: Throwable) {
            descriptor.close()
            throw failure
        }
        return object : ExportExternalDocumentDestination.Sink {
            override val output = stream

            override fun syncOrThrow() {
                // A provider-backed fd may be a pipe or a cloud-backed handle;
                // in that case fsync may fail and we MUST NOT report reread success.
                stream.fd.sync()
            }

            override fun close() {
                try { stream.close() } finally { descriptor.close() }
            }
        }
    }

    override fun openReadBack(): InputStream {
        if (!attempted.get()) throw IOException("No external write was attempted")
        return try {
            resolver.openInputStream(documentUri)
                ?: throw IOException("External provider refused fresh readback")
        } catch (error: SecurityException) {
            throw IOException("No read permission for selected document", error)
        }
    }
}
