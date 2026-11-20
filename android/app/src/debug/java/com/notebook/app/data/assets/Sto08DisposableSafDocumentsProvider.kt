package com.notebook.app.data.assets

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/**
 * DEBUG APK ONLY: an actual Android DocumentsProvider for STO-08 instrumented
 * tests. Never present in release, never connected to library.sqlite or
 * managed originals, and never used by the production export UI.
 *
 * The root is a dedicated app-private test-only directory; IDs have no path
 * separators, and caller display names are NEVER interpolated into paths.
 * This provider does not implement external-cloud persistence, DocumentsUI
 * picker grants, power-loss durability or publication completion receipts.
 */
internal class Sto08DisposableSafDocumentsProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "org.notebook.sto08.disposable.saf.documents"
        const val ROOT_ID = "sto08-test-root"
        private const val ROOT_DIRECTORY = "sto08_disposable_saf_test_documents"
        private const val MIME = "application/x-notebook"

        internal fun isolatedRoot(filesDir: File): File =
            File(filesDir, ROOT_DIRECTORY)
    }

    override fun onCreate(): Boolean = true

    private fun dir(): File {
        val files = context?.filesDir ?: throw FileNotFoundException("No test app context")
        val folder = isolatedRoot(files)
        if (!folder.exists() && !folder.mkdir()) {
            throw FileNotFoundException("Cannot create isolated SAF test root")
        }
        if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw FileNotFoundException("Linked or unsafe SAF test root")
        }
        return folder
    }

    private fun file(documentId: String): File {
        val id = when {
            documentId.startsWith("doc-") -> documentId.removePrefix("doc-")
            documentId.startsWith("deny-") -> documentId.removePrefix("deny-")
            else -> throw FileNotFoundException("Unknown disposable document ID")
        }
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) {
            throw FileNotFoundException("Noncanonical disposable document ID")
        }
        return File(dir(), documentId)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val keys = projection ?: arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
        )
        return MatrixCursor(keys).apply {
            val row = newRow()
            keys.forEach { key ->
                row.add(when (key) {
                    DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                    DocumentsContract.Root.COLUMN_FLAGS ->
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                    DocumentsContract.Root.COLUMN_TITLE -> "STO-08 disposable test documents"
                    DocumentsContract.Root.COLUMN_MIME_TYPES -> MIME
                    else -> null
                })
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val keys = projection ?: documentColumns()
        return MatrixCursor(keys).apply { addDocument(this, keys, documentId) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (parentDocumentId != ROOT_ID) {
            throw FileNotFoundException("Only disposable test root has children")
        }
        val keys = projection ?: documentColumns()
        return MatrixCursor(keys).apply {
            dir().listFiles()?.filter { f ->
                f.isFile && !Files.isSymbolicLink(f.toPath()) &&
                    (f.name.startsWith("doc-") || f.name.startsWith("deny-")) &&
                    runCatching { file(f.name) }.isSuccess
            }?.forEach { addDocument(this, keys, it.name) }
        }
    }

    private fun documentColumns() = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    private fun addDocument(cursor: MatrixCursor, keys: Array<out String>, documentId: String) {
        val root = documentId == ROOT_ID
        val item = if (root) null else file(documentId)
        if (!root && (item == null ||
                !Files.isRegularFile(item.toPath(), LinkOption.NOFOLLOW_LINKS))) {
            throw FileNotFoundException("Disposable document missing or unsafe")
        }
        val row = cursor.newRow()
        keys.forEach { key ->
            row.add(when (key) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> if (root) "Test root" else documentId
                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (root)
                    DocumentsContract.Document.MIME_TYPE_DIR else MIME
                DocumentsContract.Document.COLUMN_SIZE -> item?.length() ?: 0L
                DocumentsContract.Document.COLUMN_FLAGS -> if (root)
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                else DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                else -> null
            })
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        if (parentDocumentId != ROOT_ID || mimeType != MIME ||
            !displayName.startsWith("sto08-")
        ) throw FileNotFoundException("Only explicit disposable test creations are accepted")
        val name = (if (displayName.startsWith("sto08-deny-write-")) "deny-" else "doc-") +
            UUID.randomUUID().toString()
        Files.createFile(file(name).toPath())
        return name
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (documentId.startsWith("deny-") && mode.contains('w')) {
            throw FileNotFoundException("Injected disposable provider write refusal")
        }
        val target = file(documentId)
        if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw FileNotFoundException("Disposable provider member missing")
        }
        signal?.throwIfCanceled()
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
    }

    override fun deleteDocument(documentId: String) {
        // Explicit test teardown only; never reaches any canonical original path.
        val target = file(documentId)
        if (!Files.deleteIfExists(target.toPath())) {
            throw FileNotFoundException("Disposable test document already absent")
        }
    }
}
