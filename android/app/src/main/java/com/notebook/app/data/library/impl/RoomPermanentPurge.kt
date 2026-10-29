package com.notebook.app.data.library.impl

import com.notebook.app.data.content.validation.CanonicalContentValidator
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.data.library.model.Notebook
import com.notebook.app.data.library.repository.PurgePreview
import com.notebook.app.data.library.repository.TrashedItemKind
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.entity.FolderEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import java.security.MessageDigest

/** Internal preflight & writes. Caller holds AppDatabase.withTransaction for the entire call. */
internal class RoomPermanentPurge(private val db: AppDatabase) {
    private val folders get() = db.folderDao()
    private val notebooks get() = db.notebookDao()
    private val notes get() = db.noteDao()
    private val pages get() = db.pageDao()
    private val elements get() = db.elementDao()

    data class Plan(
        val preview: PurgePreview,
        val folderRows: List<FolderEntity>,
        val notebookRows: List<NotebookEntity>,
        val noteRows: List<NoteEntity>,
        val folderSurvivors: List<FolderEntity>,
        val notebookSurvivors: List<NotebookEntity>,
        val noteSurvivors: List<NoteEntity>,
        val quickNotesId: String?,
    )

    suspend fun preflight(batchInput: String): Plan {
        val batch = LibraryValidation.canonicalId(batchInput)
        val targetFolders = folders.listTrashedByBatch(batch)
        val targetNotebooks = notebooks.listTrashedByBatch(batch)
        val targetNotes = notes.listTrashedByBatch(batch)
        if (targetFolders.isEmpty() && targetNotebooks.isEmpty() && targetNotes.isEmpty()) {
            fail(LibraryErrorCode.NOT_FOUND, "Deletion batch does not exist")
        }
        if (folders.countActiveWithBatch(batch) != 0 ||
            notebooks.countActiveWithBatch(batch) != 0 ||
            notes.countActiveWithBatch(batch) != 0
        ) fail(LibraryErrorCode.INVALID_INPUT, "Active record incorrectly references deletion batch")
        if (targetNotebooks.any { it.systemRole != null }) {
            fail(LibraryErrorCode.PROTECTED_ENTITY, "System notebooks cannot be permanently deleted")
        }
        (targetFolders.map { it.id } + targetNotebooks.map { it.id } + targetNotes.map { it.id })
            .forEach(LibraryValidation::canonicalId)
        val folderIds = targetFolders.map { it.id }.toSet()
        val notebookIds = targetNotebooks.map { it.id }.toSet()
        val noteIds = targetNotes.map { it.id }.toSet()
        // Soft-deleting a subtree enrolls exactly one root. Multiple roots signal an
        // inconsistent/corrupt batch, not multiple independent user confirmations.
        val roots = targetFolders.filter { it.parentFolderId !in folderIds }.map { Triple(TrashedItemKind.FOLDER,it.id,it.name) } +
            targetNotebooks.filter { it.parentFolderId !in folderIds }.map { Triple(TrashedItemKind.NOTEBOOK,it.id,it.name) } +
            targetNotes.filter { it.notebookId !in notebookIds }.map { Triple(TrashedItemKind.NOTE,it.id,it.title.ifBlank { "Untitled Note" }) }
        if (roots.size != 1) fail(LibraryErrorCode.INVALID_INPUT, "Deletion batch must have exactly one root")
        val root = roots.single()
        val folderSurvivors = targetFolders.flatMap { row -> folders.listAllByOwner(row.id) }
            .filter { it.id !in folderIds }.distinctBy { it.id }
        val notebookSurvivors = targetFolders.flatMap { row -> notebooks.listAllByOwner(row.id) }
            .filter { it.id !in notebookIds }.distinctBy { it.id }
        val noteSurvivors = targetNotebooks.flatMap { row -> notes.listAllByOwner(row.id) }
            .filter { it.id !in noteIds }.distinctBy { it.id }
        if (folderSurvivors.any { it.deletedAtMillis == null || it.deletionBatchId == null || it.deletionBatchId == batch } ||
            notebookSurvivors.any { it.deletedAtMillis == null || it.deletionBatchId == null || it.deletionBatchId == batch } ||
            noteSurvivors.any { it.deletedAtMillis == null || it.deletionBatchId == null || it.deletionBatchId == batch ||
                it.originalNotebookId == null }
        ) fail(LibraryErrorCode.INVALID_INPUT, "Active or ambiguous dependent prevents purge")
        val quickNotes = notebooks.findQuickNotes()
        if (noteSurvivors.isNotEmpty() &&
            (quickNotes == null || quickNotes.parentFolderId != null || quickNotes.deletedAtMillis != null ||
                quickNotes.deletionBatchId != null || quickNotes.systemRole != Notebook.QUICK_NOTES_ROLE || quickNotes.id in notebookIds)
        ) fail(LibraryErrorCode.PROTECTED_ENTITY, "An active root Quick Notes notebook is required for rehoming")

        // Fail closed on media / malformed payloads. No files or asset tables are touched.
        val identity = mutableListOf<String>()
        for (f in targetFolders) identity.add("F|${f.id}|${f.name}|${f.parentFolderId}|${f.deletedAtMillis}|${f.deletionBatchId}")
        for (n in targetNotebooks) identity.add("B|${n.id}|${n.name}|${n.parentFolderId}|${n.deletedAtMillis}|${n.deletionBatchId}|${n.systemRole}")
        var pageCount = 0
        var elementCount = 0
        for (n in targetNotes) {
            identity.add("N|${n.id}|${n.title}|${n.notebookId}|${n.deletedAtMillis}|${n.deletionBatchId}|${n.revision}")
            val pageRows = pages.listByNote(n.id)
            if (pageRows.isEmpty()) fail(LibraryErrorCode.INVALID_INPUT, "Trashed note has no pages")
            pageRows.forEachIndexed { index, page ->
                if (page.orderIndex != index.toLong() || page.noteId != n.id ||
                    !page.width.isFinite() || page.width <= 0.0 ||
                    !page.height.isFinite() || page.height <= 0.0 || page.revision > n.revision || page.revision < 0
                ) fail(LibraryErrorCode.INVALID_INPUT, "Invalid stored page geometry or ordering")
                CanonicalContentValidator.background(page.backgroundJson)
                identity.add("P|${page.id}|${page.noteId}|${page.orderIndex}|${page.width}|${page.height}|${page.revision}|${page.backgroundJson}")
                pageCount++
                elements.listByPage(page.id).forEachIndexed { elementIndex, element ->
                    if (element.orderIndex != elementIndex.toLong() || element.pageId != page.id ||
                        element.revision > n.revision || element.revision < 0
                    ) fail(LibraryErrorCode.INVALID_INPUT, "Invalid stored element ordering or revision")
                    val (canonicalId, kind) = CanonicalContentValidator.element(element.elementJson)
                    if (canonicalId != element.id || kind != element.kind) {
                        fail(LibraryErrorCode.INVALID_INPUT, "Stored element index disagrees with canonical content")
                    }
                    identity.add("E|${element.id}|${element.pageId}|${element.orderIndex}|${element.kind}|${element.revision}|${element.elementJson}")
                    elementCount++
                }
            }
        }
        // Bind preview to all direct dependent identities as well; an intervening
        // independent Trash operation must not silently change which rows move.
        folderSurvivors.forEach { identity.add("SF|${it.id}|${it.parentFolderId}|${it.deletedAtMillis}|${it.deletionBatchId}") }
        notebookSurvivors.forEach { identity.add("SB|${it.id}|${it.parentFolderId}|${it.deletedAtMillis}|${it.deletionBatchId}") }
        noteSurvivors.forEach { identity.add("SN|${it.id}|${it.notebookId}|${it.deletedAtMillis}|${it.deletionBatchId}|${it.revision}") }
        val digest = MessageDigest.getInstance("SHA-256")
        val fingerprint = (listOf("batch:$batch") + identity.sorted()).joinToString("\n")
            .toByteArray(Charsets.UTF_8).let { bytes -> digest.digest(bytes).joinToString("") { "%02x".format(it) } }
        return Plan(
            preview = PurgePreview(batch, root.first, root.third, targetFolders.size,
                targetNotebooks.size, targetNotes.size, pageCount, elementCount,
                folderSurvivors.size, notebookSurvivors.size, noteSurvivors.size, fingerprint),
            folderRows = targetFolders, notebookRows = targetNotebooks, noteRows = targetNotes,
            folderSurvivors = folderSurvivors, notebookSurvivors = notebookSurvivors,
            noteSurvivors = noteSurvivors, quickNotesId = quickNotes?.id,
        )
    }

    suspend fun delete(plan: Plan, afterStep: (PurgeStep) -> Unit) {
        for (folder in plan.folderSurvivors) {
            check(folders.update(folder.copy(parentFolderId = null)) == 1)
        }
        for (notebook in plan.notebookSurvivors) {
            check(notebooks.update(notebook.copy(parentFolderId = null)) == 1)
        }
        for (note in plan.noteSurvivors) {
            check(notes.update(note.copy(notebookId = requireNotNull(plan.quickNotesId))) == 1)
        }
        afterStep(PurgeStep.AFTER_REHOME)
        var deletedFirstElement = false
        for (note in plan.noteRows) {
            for (page in pages.listByNote(note.id)) {
                for (element in elements.listByPage(page.id)) {
                    check(elements.deleteById(element.id) == 1)
                    if (!deletedFirstElement) {
                        deletedFirstElement = true
                        afterStep(PurgeStep.AFTER_FIRST_ELEMENT_DELETE)
                    }
                }
                check(pages.deleteById(page.id) == 1)
            }
            check(notes.deleteById(note.id) == 1)
            afterStep(PurgeStep.AFTER_NOTE_DELETE)
        }
        for (notebook in plan.notebookRows) check(notebooks.deleteById(notebook.id) == 1)
        val pending = plan.folderRows.associateBy { it.id }.toMutableMap()
        while (pending.isNotEmpty()) {
            val leaves = pending.values.filter { f -> pending.values.none { it.parentFolderId == f.id } }
            if (leaves.isEmpty()) fail(LibraryErrorCode.CYCLE_DETECTED, "Corrupt folder cycle")
            for (folder in leaves) {
                check(folders.deleteById(folder.id) == 1)
                afterStep(PurgeStep.AFTER_FOLDER_DELETE)
                pending.remove(folder.id)
            }
        }
    }

    private fun fail(code: LibraryErrorCode, message: String): Nothing =
        LibraryRepositorySupport.error(code, message)
}

/** Test-only fault hook is injected in the repository constructor, not in public UI APIs. */
enum class PurgeStep { AFTER_REHOME, AFTER_FIRST_ELEMENT_DELETE, AFTER_NOTE_DELETE, AFTER_FOLDER_DELETE }
