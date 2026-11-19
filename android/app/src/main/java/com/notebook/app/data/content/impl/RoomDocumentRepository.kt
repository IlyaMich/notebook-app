package com.notebook.app.data.content.impl

import androidx.room.withTransaction
import com.notebook.app.data.assets.RegisteredFinalizedImageDecoder
import com.notebook.app.data.assets.VerifiedDecodedImageInfo
import com.notebook.app.data.content.model.InsertVerifiedImageCommand
import com.notebook.app.data.content.model.ValidatedInsertVerifiedImage
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.entity.ElementAssetReferenceEntity
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.content.validation.CanonicalContentValidator
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.PageEntity
import org.json.JSONObject


/**
 * C2C-3C: transaction-time identity check against the previously leased,
 * fully decoded original. Includes its canonical relative path; a matching
 * digest/MIME/length alone is insufficient if the stored locator changed.
 * This is a pure predicate: it does not verify or open files, acquire locks,
 * authorize cleanup, or replace the pre-transaction integrity inspection.
 */
internal fun matchesVerifiedImageMetadata(
    asset: AssetEntity,
    verified: VerifiedDecodedImageInfo,
): Boolean {
    if (asset.id != verified.assetId || asset.state != "finalized" ||
        asset.sha256 != verified.sha256 || asset.mimeType != verified.mimeType ||
        asset.byteSize != verified.encodedBytes
    ) return false
    if (!Regex("[0-9a-f]{64}").matches(verified.sha256)) return false
    val ext = when (verified.mimeType) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        else -> return false
    }
    val sha = verified.sha256
    return asset.relativePath ==
        "assets/${sha.substring(0, 2)}/${sha.substring(2, 4)}/$sha.$ext"
}

/** Internal test-only injection token, not available through DocumentRepository. */
internal class VerifiedImagePrecommitTestHook(val callback: suspend () -> Unit)

/** Each successful command is one SQLite transaction and at most one revision advance.
 * The two-phase ordering writes rely on the V2 unique indexes. No UI caller may use raw DAOs.
 */
class RoomDocumentRepository private constructor(
    private val db: AppDatabase,
    private val clock: () -> Long,
    private val verifiedImageRoot: File?,
    private val afterVerifiedImageEdgeInsertedForProcessDeathTest: (suspend () -> Unit)?,
) : DocumentRepository {
    /** Normal repository construction never exposes or installs the test seam. */
    constructor(
        db: AppDatabase,
        clock: () -> Long = System::currentTimeMillis,
        verifiedImageRoot: File? = null,
    ) : this(db, clock, verifiedImageRoot, null)

    /** Internal test-only constructor: immutable callback, acquired-lease -> Room order unchanged. */
    internal constructor(
        db: AppDatabase,
        clock: () -> Long,
        verifiedImageRoot: File,
        hook: VerifiedImagePrecommitTestHook,
    ) : this(db, clock, verifiedImageRoot, hook.callback)

    /**
     * C2C-3C-I2B: narrow internal image insertion. Not a DocumentEdit operation.
     * Asset verification + full decode happen inside the single acquisition lease,
     * followed by the repository-owned Room transaction under that same lease.
     * The nullable root intentionally fails closed for existing production wiring.
     */
    override suspend fun insertVerifiedImage(command: InsertVerifiedImageCommand): DocumentCommit {
        val request = command.validated()
        val root = verifiedImageRoot ?: fail(
            LibraryErrorCode.UNSUPPORTED_CONTENT, "Verified image writer is not configured"
        )
        val decoder = RegisteredFinalizedImageDecoder(root, db.assetDao())
        return decoder.withInspectedAssetLease(request.assetId) { verified ->
            currentCoroutineContext().ensureActive()
            insertVerifiedImageInTransaction(request, verified)
        }
    }

    /**
     * Verified image-to-image replacement. A newly selected asset is fully
     * inspected under its publication/acquisition lease before the Room edit.
     * The previous owner edge, element JSON and note revision change atomically.
     * No asset bytes or metadata are reclaimed, including the previous original.
     */
    override suspend fun replaceVerifiedImage(command: InsertVerifiedImageCommand): DocumentCommit {
        val request = command.validated()
        if (request.at != null) {
            fail(LibraryErrorCode.INVALID_INPUT, "Image replacement cannot change element order")
        }
        val root = verifiedImageRoot ?: fail(
            LibraryErrorCode.UNSUPPORTED_CONTENT, "Verified image writer is not configured"
        )
        return RegisteredFinalizedImageDecoder(root, db.assetDao())
            .withInspectedAssetLease(request.assetId) { verified ->
                currentCoroutineContext().ensureActive()
                replaceVerifiedImageInTransaction(request, verified)
            }
    }

    private suspend fun replaceVerifiedImageInTransaction(
        request: ValidatedInsertVerifiedImage,
        verified: VerifiedDecodedImageInfo,
    ): DocumentCommit = db.withTransaction {
        currentCoroutineContext().ensureActive()
        val note = db.noteDao().findById(request.noteId)
            ?: fail(LibraryErrorCode.NOT_FOUND, "Image target note not found")
        if (note.revision != request.expectedNoteRevision) {
            fail(LibraryErrorCode.INVALID_INPUT, "Stale image replacement")
        }
        if (note.deletedAtMillis != null || note.revision == Long.MAX_VALUE) {
            fail(LibraryErrorCode.INACTIVE_OWNER, "Image target is inactive")
        }
        val notebook = db.notebookDao().findById(note.notebookId)
            ?: fail(LibraryErrorCode.INACTIVE_OWNER, "Image notebook missing")
        if (notebook.deletedAtMillis != null) {
            fail(LibraryErrorCode.INACTIVE_OWNER, "Image notebook is in Trash")
        }
        if (db.assetOwnershipDao().readFailClosedGateStatus() != "UNVERIFIED") {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Image ownership gate rejects replacement")
        }
        val page = db.pageDao().findById(request.pageId)
            ?: fail(LibraryErrorCode.NOT_FOUND, "Image page not found")
        if (page.noteId != note.id) {
            fail(LibraryErrorCode.NOT_FOUND, "Image page does not belong to note")
        }
        val old = db.elementDao().findById(request.elementId)
            ?: fail(LibraryErrorCode.NOT_FOUND, "Image element not found")
        if (old.pageId != page.id || !isImageContent(old)) {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Image target identity mismatch")
        }
        if (isLocked(old)) {
            fail(LibraryErrorCode.PROTECTED_ENTITY, "Locked image cannot be replaced")
        }
        val oldAssetId = verifiedImageOwnerAssetId(old)
        val asset = db.assetDao().findById(request.assetId)
            ?: fail(LibraryErrorCode.NOT_FOUND, "Verified image metadata disappeared")
        if (!matchesVerifiedImageMetadata(asset, verified)) {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Verified image metadata changed")
        }
        val nextJson = canonicalImageJson(request)
        if (nextJson == old.elementJson) {
            return@withTransaction DocumentCommit(note.id, note.revision, note.updatedAtMillis)
        }
        val nextRevision = note.revision + 1L
        if (oldAssetId != request.assetId &&
            db.assetOwnershipDao().changeElementAssetIfMatches(
                old.id, oldAssetId, request.assetId
            ) != 1
        ) fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Image owner reference changed")
        check(db.elementDao().update(old.copy(elementJson = nextJson, revision = nextRevision)) == 1)
        currentCoroutineContext().ensureActive()
        val now = maxOf(clock(), note.updatedAtMillis)
        check(db.noteDao().update(note.copy(revision = nextRevision, updatedAtMillis = now)) == 1)
        currentCoroutineContext().ensureActive()
        DocumentCommit(note.id, nextRevision, now)
    }

    private suspend fun insertVerifiedImageInTransaction(
        request: ValidatedInsertVerifiedImage,
        verified: VerifiedDecodedImageInfo,
    ): DocumentCommit = db.withTransaction {
        currentCoroutineContext().ensureActive()
        val note = db.noteDao().findById(request.noteId)
            ?: fail(LibraryErrorCode.NOT_FOUND, "Image target note not found")
        if (note.revision != request.expectedNoteRevision) {
            fail(LibraryErrorCode.INVALID_INPUT, "Stale image insertion: note revision changed")
        }
        if (note.deletedAtMillis != null) {
            fail(LibraryErrorCode.INACTIVE_OWNER, "Image target note is in Trash")
        }
        val notebook = db.notebookDao().findById(note.notebookId)
            ?: fail(LibraryErrorCode.INACTIVE_OWNER, "Image target notebook missing")
        if (notebook.deletedAtMillis != null) {
            fail(LibraryErrorCode.INACTIVE_OWNER, "Image target notebook is in Trash")
        }
        if (note.revision == Long.MAX_VALUE) {
            fail(LibraryErrorCode.INVALID_INPUT, "Note revision exhausted")
        }
        if (db.assetOwnershipDao().readFailClosedGateStatus() != "UNVERIFIED") {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Image ownership safety gate rejects insertion")
        }
        val pages = db.pageDao().listByNote(note.id)
        if (pages.isEmpty()) fail(LibraryErrorCode.INVALID_INPUT, "Missing first page")
        requireContiguous(pages.map { it.orderIndex })
        val page = ownedPage(pages, request.pageId)
        // Generic media validation stays restrictive; the only privileged bypass is
        // constructing this one canonical image envelope after full verification.
        if (isPdfBackground(page.backgroundJson)) {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "PDF-backed image target is unsupported")
        }
        CanonicalContentValidator.background(page.backgroundJson)
        rejectIndexedPageEdge(page.id)
        val existing = elementsFor(page.id)
        for (row in existing) {
            if (isImageContent(row)) {
                fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Only first image on a safe page is supported")
            }
            val (canonicalId, canonicalKind) = CanonicalContentValidator.element(row.elementJson)
            if (canonicalId != row.id || canonicalKind != row.kind) {
                fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Inconsistent existing element representation")
            }
        }
        rejectIndexedElementEdges(existing)
        if (db.elementDao().findById(request.elementId) != null ||
            db.assetOwnershipDao().findElementReference(request.elementId) != null
        ) {
            fail(LibraryErrorCode.INVALID_INPUT, "Image element ID already occupied")
        }
        val position = insertionAt(request.at, existing.size)
        val asset = db.assetDao().findById(request.assetId)
            ?: fail(LibraryErrorCode.NOT_FOUND, "Verified image metadata disappeared")
        if (!matchesVerifiedImageMetadata(asset, verified)) {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Verified image metadata changed")
        }
        val nextRevision = note.revision + 1L
        val envelope = canonicalImageJson(request)
        db.elementDao().insert(ElementEntity(
            id = request.elementId, pageId = page.id,
            orderIndex = existing.size.toLong(), kind = "image",
            elementJson = envelope, revision = nextRevision,
        ))
        db.assetOwnershipDao().insertElementReference(
            ElementAssetReferenceEntity(request.elementId, request.assetId)
        )
        // Host-driven test-only pause AFTER both owner rows are written but
        // BEFORE the outer Room transaction commits. Default null is inert.
        // Never acquire an asset lease or register files from this callback.
        afterVerifiedImageEdgeInsertedForProcessDeathTest?.invoke()
        if (position != existing.size) {
            val order = existing.map { it.id }.toMutableList().also {
                it.add(position, request.elementId)
            }
            setElementOrder(db.elementDao().listByPage(page.id), order, nextRevision)
        }
        currentCoroutineContext().ensureActive()
        val now = maxOf(clock(), note.updatedAtMillis)
        check(db.noteDao().update(note.copy(revision = nextRevision, updatedAtMillis = now)) == 1)
        currentCoroutineContext().ensureActive()
        DocumentCommit(note.id, nextRevision, now)
    }

    private fun canonicalImageJson(request: ValidatedInsertVerifiedImage): String {
        val frame = JSONObject()
            .put("x", request.frame.x)
            .put("y", request.frame.y)
            .put("width", request.frame.width)
            .put("height", request.frame.height)
        val payload = JSONObject()
            .put("assetId", request.assetId)
            .put("frame", frame)
            .put("opacity", request.opacity)
        return JSONObject()
            .put("id", request.elementId)
            .put("type", "image")
            .put("transform", JSONArray(request.transform))
            .put("locked", false)
            .put("payload", payload)
            .toString()
    }

    override suspend fun load(noteId: String): StoredNoteContent = db.withTransaction {
        val id = LibraryValidation.canonicalId(noteId)
        val note = db.noteDao().findById(id) ?: fail(LibraryErrorCode.NOT_FOUND, "Note not found")
        val pages = db.pageDao().listByNote(id).map { page ->
            StoredPage(
                id = page.id, orderIndex = page.orderIndex,
                width = page.width, height = page.height, backgroundJson = page.backgroundJson,
                revision = page.revision,
                elements = db.elementDao().listByPage(page.id).map { element ->
                    StoredElement(element.id, element.orderIndex, element.kind, element.elementJson, element.revision)
                },
            )
        }
        StoredNoteContent(note.id, note.revision, pages)
    }

    override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit =
        commitInTransaction(noteId, expectedRevision = null, edit = edit)

    override suspend fun commitIfRevision(
        noteId: String,
        expectedRevision: Long,
        edit: DocumentEdit,
    ): DocumentCommit {
        require(expectedRevision >= 0L) { "Expected note revision cannot be negative" }
        return commitInTransaction(noteId, expectedRevision, edit)
    }

    /** Keep both guarded and legacy writes inside the same transaction boundary. */
    private suspend fun commitInTransaction(
        noteId: String,
        expectedRevision: Long?,
        edit: DocumentEdit,
    ): DocumentCommit = db.withTransaction {
        val id = LibraryValidation.canonicalId(noteId)
        val note = db.noteDao().findById(id) ?: fail(LibraryErrorCode.NOT_FOUND, "Note not found")
        if ((edit is DocumentEdit.ApplyPartialStrokeReplacements ||
                edit is DocumentEdit.RestorePartialStrokeReplacements) && expectedRevision == null) {
            fail(LibraryErrorCode.INVALID_INPUT, "Partial eraser edits require a guarded commit")
        }
        if (edit is DocumentEdit.RestoreRemovedStrokes && expectedRevision == null) {
            fail(LibraryErrorCode.INVALID_INPUT, "Stroke restoration requires a guarded commit")
        }
        if (expectedRevision != null && note.revision != expectedRevision) {
            fail(LibraryErrorCode.INVALID_INPUT, "Stale document edit: note revision has changed")
        }
        if (note.deletedAtMillis != null) fail(LibraryErrorCode.INACTIVE_OWNER, "Note is in Trash")
        val notebook = db.notebookDao().findById(note.notebookId)
            ?: fail(LibraryErrorCode.INACTIVE_OWNER, "Notebook is missing")
        if (notebook.deletedAtMillis != null) fail(LibraryErrorCode.INACTIVE_OWNER, "Notebook is in Trash")
        val pages = db.pageDao().listByNote(id)
        if (pages.isEmpty()) fail(LibraryErrorCode.INVALID_INPUT, "Note has no first page")
        requireContiguous(pages.map { it.orderIndex })
        if (note.revision == Long.MAX_VALUE) fail(LibraryErrorCode.INVALID_INPUT, "Note revision exhausted")
        val nextRevision = note.revision + 1L
        val changed = perform(id, pages, edit, nextRevision)
        val result = if (changed) {
            val now = maxOf(clock(), note.updatedAtMillis)
            val updated = note.copy(revision = nextRevision, updatedAtMillis = now)
            check(db.noteDao().update(updated) == 1)
            updated
        } else note
        DocumentCommit(result.id, result.revision, result.updatedAtMillis)
    }

    private suspend fun perform(
        noteId: String, pages: List<PageEntity>, edit: DocumentEdit, revision: Long,
    ): Boolean = when (edit) {
        is DocumentEdit.InsertPage -> {
            CanonicalContentValidator.page(edit.spec)
            val id = LibraryValidation.canonicalId(edit.id)
            val at = insertionAt(edit.at, pages.size)
            db.pageDao().insert(PageEntity(
                id = id, noteId = noteId, orderIndex = pages.size.toLong(),
                width = edit.spec.width, height = edit.spec.height,
                backgroundJson = edit.spec.backgroundJson, revision = revision,
            ))
            if (at != pages.size) {
                val order = pages.map { it.id }.toMutableList().also { it.add(at, id) }
                setPageOrder(db.pageDao().listByNote(noteId), order, revision)
            }
            true
        }
        is DocumentEdit.RemovePage -> {
            val page = ownedPage(pages, edit.id)
            if (pages.size == 1) fail(LibraryErrorCode.INVALID_INPUT, "Cannot delete the last page")
            // Release only validated ownership projections, never asset bytes or
            // metadata. Page/element owner changes and the revision commit remain
            // within the same Room transaction; any mismatch rolls everything back.
            val pdfAssetId = verifiedPdfPageOwnerAssetId(page)
            val elements = elementsFor(page.id)
            for (element in elements) {
                if (isImageContent(element)) verifiedImageOwnerAssetId(element)
                else {
                    val (canonicalId, kind) = CanonicalContentValidator.element(element.elementJson)
                    if (canonicalId != element.id || kind != element.kind) {
                        fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Inconsistent page element")
                    }
                    rejectIndexedElementEdges(listOf(element))
                }
            }
            for (element in elements) {
                if (isImageContent(element)) releaseVerifiedImageOwner(element)
                check(db.elementDao().deleteById(element.id) == 1)
            }
            if (pdfAssetId != null) {
                if (db.assetOwnershipDao().removePageReferenceIfMatches(page.id, pdfAssetId) != 1) {
                    fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "PDF page reference changed")
                }
            }
            check(db.pageDao().deleteById(page.id) == 1)
            val remaining = db.pageDao().listByNote(noteId)
            setPageOrder(remaining, remaining.map { it.id }, revision)
            true
        }
        is DocumentEdit.ReorderPages -> {
            setPageOrder(pages, edit.idsInOrder, revision)
        }
        is DocumentEdit.ChangeBackground -> {
            val page = ownedPage(pages, edit.pageId)
            // The generic command accepts only asset-free destination backgrounds.
            // Releasing an existing PDF owner is a separate checked operation in
            // this same transaction; no new asset can be attached through it.
            CanonicalContentValidator.background(edit.backgroundJson)
            if (page.backgroundJson == edit.backgroundJson) false else {
                val priorPdfId = verifiedPdfPageOwnerAssetId(page)
                if (priorPdfId != null) {
                    if (db.assetOwnershipDao().removePageReferenceIfMatches(page.id, priorPdfId) != 1) {
                        fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "PDF background reference changed")
                    }
                }
                check(db.pageDao().update(page.copy(backgroundJson = edit.backgroundJson, revision = revision)) == 1)
                true
            }
        }
        is DocumentEdit.InsertElements -> {
            val page = ownedPage(pages, edit.pageId)
            val existing = elementsFor(page.id)
            val at = insertionAt(edit.at, existing.size)
            if (edit.envelopes.isEmpty()) false else {
                rejectIndexedPageEdge(page.id)
                val parsed = edit.envelopes.map { raw ->
                    val (id, kind) = CanonicalContentValidator.element(raw)
                    ElementEntity(id, page.id, 0, kind, raw, revision)
                }
                uniqueIds(parsed.map { it.id })
                parsed.forEachIndexed { index, element ->
                    db.elementDao().insert(element.copy(orderIndex = existing.size.toLong() + index))
                }
                if (at != existing.size) {
                    val desired = existing.map { it.id }.toMutableList().also {
                        it.addAll(at, parsed.map { item -> item.id })
                    }
                    setElementOrder(db.elementDao().listByPage(page.id), desired, revision)
                }
                true
            }
        }
        is DocumentEdit.ReplaceElements -> {
            val page = ownedPage(pages, edit.pageId)
            val existing = elementsFor(page.id).associateBy { it.id }
            val parsed = edit.envelopes.map { raw ->
                val (id, kind) = CanonicalContentValidator.element(raw)
                val old = existing[id] ?: fail(LibraryErrorCode.NOT_FOUND, "Element not on specified page")
                // Valid text/shape replacements must not erase an existing image reference.
                if (isImageContent(old)) {
                    fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Image replacement requires asset references")
                }
                if (isLocked(old)) fail(LibraryErrorCode.PROTECTED_ENTITY, "Locked element cannot be modified")
                old.copy(kind = kind, elementJson = raw, revision = revision)
            }
            uniqueIds(parsed.map { it.id })
            val changed = parsed.filter { it.elementJson != existing.getValue(it.id).elementJson }
            rejectIndexedElementEdges(changed)
            changed.forEach { check(db.elementDao().update(it) == 1) }
            changed.isNotEmpty()
        }
        is DocumentEdit.RemoveElements -> {
            val page = ownedPage(pages, edit.pageId)
            val existing = elementsFor(page.id)
            val ids = edit.ids.map(LibraryValidation::canonicalId)
            uniqueIds(ids)
            if (ids.isEmpty()) false else {
                val selected = requireSelected(existing, ids)
                if (selected.any(::isLocked)) fail(LibraryErrorCode.PROTECTED_ENTITY, "Locked element cannot be removed")
                // Image-owner release and row deletion share this same Room transaction.
                // Never remove the original bytes or metadata here. A missing or
                // inconsistent image projection aborts the entire logical edit.
                for (row in selected) {
                    if (isImageContent(row)) releaseVerifiedImageOwner(row)
                    else rejectIndexedElementEdges(listOf(row))
                }
                ids.forEach { check(db.elementDao().deleteById(it) == 1) }
                val remaining = db.elementDao().listByPage(page.id)
                setElementOrder(remaining, remaining.map { it.id }, revision)
                true
            }
        }
        is DocumentEdit.RestoreRemovedStrokes -> {
            val page = ownedPage(pages, edit.pageId)
            val surviving = elementsFor(page.id)
            if (edit.envelopes.isEmpty()) {
                fail(LibraryErrorCode.INVALID_INPUT, "Restoration requires removed strokes")
            }

            // Validate every restored envelope BEFORE inserting any rows.
            // This is a narrow eraser inverse, not an arbitrary element importer.
            val restoring = edit.envelopes.map { raw ->
                val (id, kind) = CanonicalContentValidator.element(raw)
                if (kind != "stroke") {
                    fail(LibraryErrorCode.INVALID_INPUT, "Only erased strokes can be restored")
                }
                ElementEntity(id, page.id, 0L, kind, raw, revision)
            }
            val restoringIds = restoring.map { it.id }
            uniqueIds(restoringIds)
            if (restoring.any(::isLocked)) {
                fail(LibraryErrorCode.PROTECTED_ENTITY, "Locked strokes cannot be restored by eraser undo")
            }

            val originalOrder = edit.idsInOrder.map(LibraryValidation::canonicalId)
            uniqueIds(originalOrder)
            val existingIds = surviving.map { it.id }
            val restoredIdSet = restoringIds.toSet()
            val existingIdSet = existingIds.toSet()
            if (restoredIdSet.any { it in existingIdSet }) {
                fail(LibraryErrorCode.INVALID_INPUT, "Restored element already exists on page")
            }
            if (originalOrder.size != existingIds.size + restoringIds.size ||
                originalOrder.toSet() != existingIdSet + restoredIdSet ||
                originalOrder.filter { it !in restoredIdSet } != existingIds
            ) {
                fail(LibraryErrorCode.INVALID_INPUT, "Current survivors differ from original eraser snapshot")
            }
            // IDs are note-wide unique. A newly occupied ID on ANOTHER page
            // must fail safely rather than attaching an existing object here.
            for (restoredId in restoringIds) {
                if (db.elementDao().findById(restoredId) != null) {
                    fail(LibraryErrorCode.INVALID_INPUT, "Restored element ID is already occupied")
                }
            }

            // New rows initially occupy a free contiguous tail. The existing
            // two-phase ordering helper then places them at their exact former
            // positions in this same transaction, including around locked rows.
            restoring.forEachIndexed { index, row ->
                db.elementDao().insert(row.copy(orderIndex = surviving.size.toLong() + index))
            }
            setElementOrder(db.elementDao().listByPage(page.id), originalOrder, revision)
            true
        }
        is DocumentEdit.ApplyPartialStrokeReplacements ->
            applyPartialStrokeReplacements(
                ownedPage(pages, edit.pageId), edit.originalIdsInOrder,
                edit.resultingIdsInOrder, edit.replacements, undo = false, revision = revision,
            )
        is DocumentEdit.RestorePartialStrokeReplacements ->
            applyPartialStrokeReplacements(
                ownedPage(pages, edit.pageId), edit.originalIdsInOrder,
                edit.resultingIdsInOrder, edit.replacements, undo = true, revision = revision,
            )
        is DocumentEdit.ReorderElements -> {
            val page = ownedPage(pages, edit.pageId)
            val existing = elementsFor(page.id)
            val requested = edit.idsInOrder.map(LibraryValidation::canonicalId)
            if (existing.map { it.id } != requested && existing.any(::isLocked)) {
                fail(LibraryErrorCode.PROTECTED_ENTITY, "Locked element cannot be reordered")
            }
            setElementOrder(existing, requested, revision)
        }
        is DocumentEdit.TransferElements -> {
            val from = ownedPage(pages, edit.fromPageId)
            val to = ownedPage(pages, edit.toPageId)
            if (from.id == to.id) fail(LibraryErrorCode.INVALID_INPUT, "Transfer requires different pages")
            val source = elementsFor(from.id)
            val destination = elementsFor(to.id)
            val ids = edit.ids.map(LibraryValidation::canonicalId)
            uniqueIds(ids)
            val at = insertionAt(edit.at, destination.size)
            if (ids.isEmpty()) false else {
                val selected = requireSelected(source, ids)
                if (selected.any(::isLocked)) fail(LibraryErrorCode.PROTECTED_ENTITY, "Locked element cannot move")
                // A page move preserves the element's global identity and its
                // existing owner edge; no new asset attachment or file access occurs.
                // Validate each edge before changing either page's element order.
                for (row in selected) {
                    if (isImageContent(row)) verifiedImageOwnerAssetId(row)
                    else rejectIndexedElementEdges(listOf(row))
                }
                val movingInRenderOrder = source.filter { it.id in ids.toSet() }
                movingInRenderOrder.forEachIndexed { index, row ->
                    check(db.elementDao().update(row.copy(
                        pageId = to.id,
                        orderIndex = destination.size.toLong() + index,
                        revision = revision,
                    )) == 1)
                }
                val remainingSource = db.elementDao().listByPage(from.id)
                setElementOrder(remainingSource, remainingSource.map { it.id }, revision)
                val finalTarget = destination.map { it.id }.toMutableList().also {
                    it.addAll(at, movingInRenderOrder.map { row -> row.id })
                }
                setElementOrder(db.elementDao().listByPage(to.id), finalTarget, revision)
                true
            }
        }
    }

    /**
     * Atomic, reversible, exact-byte partial-stroke replacement. One transaction
     * owns preflight, deletions, inserts, and ordering. The same envelope pairs
     * are used in both directions, so redo does not allocate replacement IDs.
     *
     * A caller cannot use this as arbitrary reordering or arbitrary brush editing:
     * every source is an unlocked canonical stroke and each generated fragment
     * has the same brush and affine transform (and remains unlocked).
     */
    private suspend fun applyPartialStrokeReplacements(
        page: PageEntity,
        originalOrder: List<String>,
        resultingOrder: List<String>,
        replacements: List<DocumentEdit.PartialStrokeReplacement>,
        undo: Boolean,
        revision: Long,
    ): Boolean {
        if (replacements.isEmpty() || replacements.size > 8192 ||
            replacements.sumOf { it.fragmentEnvelopes.size.toLong() } > 8192L
        ) fail(LibraryErrorCode.INVALID_INPUT, "Invalid partial eraser replacement count")
        val existing = elementsFor(page.id)
        val originals = originalOrder.map(LibraryValidation::canonicalId)
        val result = resultingOrder.map(LibraryValidation::canonicalId)
        uniqueIds(originals)
        uniqueIds(result)
        val expected = if (undo) result else originals
        val desired = if (undo) originals else result
        if (existing.map { it.id } != expected) {
            fail(LibraryErrorCode.INVALID_INPUT, "Page order differs from partial eraser snapshot")
        }

        val removedSources = replacements.map { entry ->
            val (id, kind) = CanonicalContentValidator.element(entry.originalEnvelope)
            if (kind != "stroke") fail(LibraryErrorCode.INVALID_INPUT, "Partial eraser source is not stroke")
            val original = ElementEntity(id, page.id, 0L, kind, entry.originalEnvelope, revision)
            if (isLocked(original)) fail(LibraryErrorCode.PROTECTED_ENTITY, "Locked stroke cannot be split")
            original
        }
        val generated = replacements.map { entry ->
            entry.fragmentEnvelopes.map { raw ->
                val (id, kind) = CanonicalContentValidator.element(raw)
                if (kind != "stroke") {
                    fail(LibraryErrorCode.INVALID_INPUT, "Partial eraser fragment is not stroke")
                }
                val row = ElementEntity(id, page.id, 0L, kind, raw, revision)
                if (isLocked(row)) fail(LibraryErrorCode.PROTECTED_ENTITY, "Locked eraser fragment")
                row
            }
        }
        val sourceIds = removedSources.map { it.id }
        val generatedRows = generated.flatten()
        val generatedIds = generatedRows.map { it.id }
        uniqueIds(sourceIds + generatedIds)

        // Fragments are not allowed to alter brush, transform or lock state.
        // Check semantic JSON objects, not their textual formatting.
        replacements.forEachIndexed { index, entry ->
            val source = JSONObject(entry.originalEnvelope)
            val sourceBrush = source.getJSONObject("payload").getJSONObject("brush")
            val sourceTransform = source.getJSONArray("transform")
            generated[index].forEach { row ->
                val candidate = JSONObject(row.elementJson)
                val brush = candidate.getJSONObject("payload").getJSONObject("brush")
                val transform = candidate.getJSONArray("transform")
                val sameBrush = brush.getString("kind") == sourceBrush.getString("kind") &&
                    brush.getDouble("width") == sourceBrush.getDouble("width") &&
                    brush.getString("color") == sourceBrush.getString("color") &&
                    brush.getDouble("opacity") == sourceBrush.getDouble("opacity")
                val sameTransform = (0 until 6).all { axis ->
                    transform.getDouble(axis) == sourceTransform.getDouble(axis)
                }
                if (!sameBrush || !sameTransform) {
                    fail(LibraryErrorCode.INVALID_INPUT, "Partial fragment changes brush or transform")
                }
            }
        }

        // Verify the entire claimed pre/post render orders, including survivors.
        val originalSet = sourceIds.toSet()
        val fragmentSet = generatedIds.toSet()
        if (!originals.containsAll(sourceIds) || !result.containsAll(generatedIds) ||
            originals.any { it in fragmentSet } || result.any { it in originalSet } ||
            originals.filterNot { it in originalSet } != result.filterNot { it in fragmentSet }
        ) fail(LibraryErrorCode.INVALID_INPUT, "Invalid partial eraser survivor order")
        val expanding = originals.flatMap { id ->
            val idx = sourceIds.indexOf(id)
            if (idx < 0) listOf(id) else generated[idx].map { it.id }
        }
        if (expanding != result) {
            fail(LibraryErrorCode.INVALID_INPUT, "Fragments do not occupy original stroke positions")
        }

        val deleting = if (undo) generatedRows else removedSources
        val inserting = if (undo) removedSources else generatedRows
        val existingById = existing.associateBy { it.id }
        for (row in deleting) {
            val old = existingById[row.id] ?: fail(LibraryErrorCode.INVALID_INPUT, "Expected stroke is missing")
            if (old.kind != "stroke" || old.elementJson != row.elementJson || isLocked(old)) {
                fail(LibraryErrorCode.INVALID_INPUT, "Partial eraser source bytes have changed")
            }
        }
        val deletingSet = deleting.map { it.id }.toSet()
        if (expected.toSet() != (existing.map { it.id }.toSet()) ||
            desired.size != existing.size - deleting.size + inserting.size
        ) fail(LibraryErrorCode.INVALID_INPUT, "Inconsistent partial eraser snapshot")
        for (row in inserting) {
            // IDs are unique across the entire database, not merely this page.
            if (db.elementDao().findById(row.id) != null) {
                fail(LibraryErrorCode.INVALID_INPUT, "Partial eraser element ID collision")
            }
        }
        rejectIndexedPageEdge(page.id)
        rejectIndexedElementEdges(existing.filter { it.id in deletingSet })
        deleting.forEach { check(db.elementDao().deleteById(it.id) == 1) }
        val survivors = db.elementDao().listByPage(page.id)
        // After deletion the surviving order indices can have holes. Place new
        // rows beyond the greatest remaining index, then two-phase reorder.
        val maxIndex = survivors.maxOfOrNull { it.orderIndex } ?: -1L
        if (maxIndex > Long.MAX_VALUE - inserting.size - 1L) {
            fail(LibraryErrorCode.INVALID_INPUT, "Order index exhausted")
        }
        inserting.forEachIndexed { index, row ->
            db.elementDao().insert(row.copy(orderIndex = maxIndex + 1L + index))
        }
        setElementOrder(db.elementDao().listByPage(page.id), desired, revision)
        return true
    }

    /**
     * Return the indexed PDF asset if and only if the canonical page background,
     * durable reference, and finalized asset metadata agree. Asset-free pages
     * must also be free of unexpected indexed references.
     *
     * This is deletion preflight only: no PDF bytes are read or reclaimed, and
     * this result never authorizes original-asset garbage collection.
     */
    private suspend fun verifiedPdfPageOwnerAssetId(page: PageEntity): String? {
        val background = try {
            JSONObject(page.backgroundJson)
        } catch (_: org.json.JSONException) {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Malformed page background")
        }
        if (background.optString("type") != "pdfPage") {
            CanonicalContentValidator.background(page.backgroundJson)
            rejectIndexedPageEdge(page.id)
            return null
        }
        if (background.keys().asSequence().toSet() !=
            setOf("type", "assetId", "pageIndex", "displayBox") ||
            background.optString("displayBox") != "crop" ||
            !background.has("pageIndex") ||
            background.opt("pageIndex") !is Int ||
            background.getInt("pageIndex") < 0
        ) fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Invalid PDF background")
        val assetId = try {
            LibraryValidation.canonicalId(background.getString("assetId"))
        } catch (_: Exception) {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Invalid PDF asset ID")
        }
        val edge = db.assetOwnershipDao().findPageReference(page.id)
            ?: fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "PDF page owner has no indexed reference")
        val asset = db.assetDao().findById(assetId)
        if (edge.assetId != assetId || asset?.state != "finalized" ||
            asset?.mimeType != "application/pdf"
        ) fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "PDF owner and reference disagree")
        return assetId
    }

    /**
     * Validate an existing image's authoritative canonical owner and indexed edge.
     * Page transfer preserves the global element ID and leaves this edge unchanged.
     * This does not certify ownership-index completeness, physical bytes or GC.
     */
    private suspend fun verifiedImageOwnerAssetId(row: ElementEntity): String {
        val parsed = try {
            JSONObject(row.elementJson)
        } catch (_: org.json.JSONException) {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Malformed image owner")
        }
        if (row.kind != "image" || parsed.optString("type") != "image" ||
            parsed.optString("id") != row.id ||
            parsed.keys().asSequence().toSet() !=
                setOf("id", "type", "transform", "locked", "payload") ||
            parsed.opt("locked") != false
        ) fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Inconsistent image owner")

        val payload = parsed.optJSONObject("payload")
            ?: fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Missing image payload")
        if (payload.keys().asSequence().toSet() != setOf("assetId", "frame", "opacity")) {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Unsupported image payload")
        }
        val assetId = try {
            LibraryValidation.canonicalId(payload.getString("assetId"))
        } catch (_: Exception) {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Invalid image asset ID")
        }
        val frame = payload.optJSONObject("frame")
            ?: fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Missing image frame")
        if (frame.keys().asSequence().toSet() != setOf("x", "y", "width", "height") ||
            listOf("x", "y", "width", "height").any { !frame.optDouble(it).isFinite() } ||
            frame.optDouble("width") <= 0 || frame.optDouble("height") <= 0 ||
            !payload.optDouble("opacity").isFinite() ||
            payload.optDouble("opacity") !in 0.0..1.0
        ) fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Invalid image geometry")
        val transform = parsed.optJSONArray("transform")
            ?: fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Missing image transform")
        if (transform.length() != 6 || (0 until 6).any { !transform.optDouble(it).isFinite() }) {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Invalid image transform")
        }
        val determinant = transform.optDouble(0) * transform.optDouble(3) -
            transform.optDouble(1) * transform.optDouble(2)
        if (!determinant.isFinite() || determinant == 0.0) {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Non-invertible image transform")
        }
        val dao = db.assetOwnershipDao()
        val edge = dao.findElementReference(row.id)
            ?: fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Image owner has no indexed reference")
        if (edge.assetId != assetId ||
            db.assetDao().findById(assetId)?.state != "finalized"
        ) fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Image owner and reference disagree")
        return assetId
    }

    /** Ownership release and element deletion must occur in one Room transaction. */
    private suspend fun releaseVerifiedImageOwner(row: ElementEntity) {
        val assetId = verifiedImageOwnerAssetId(row)
        if (db.assetOwnershipDao().removeElementReferenceIfMatches(row.id, assetId) != 1) {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Image owner reference changed")
        }
    }

    /** A mismatched legacy index entry cannot be silently removed by an asset-free edit. */
    private suspend fun rejectIndexedPageEdge(pageId: String) {
        if (db.assetOwnershipDao().findPageReference(pageId) != null) {
            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Indexed page ownership requires asset-aware editing")
        }
    }

    private suspend fun rejectIndexedElementEdges(rows: List<ElementEntity>) {
        for (row in rows) {
            if (db.assetOwnershipDao().findElementReference(row.id) != null) {
                fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Indexed element ownership requires asset-aware editing")
            }
        }
    }

    private fun ownedPage(pages: List<PageEntity>, id: String): PageEntity {
        val canonical = LibraryValidation.canonicalId(id)
        return pages.firstOrNull { it.id == canonical }
            ?: fail(LibraryErrorCode.NOT_FOUND, "Page does not belong to this note")
    }

    private suspend fun elementsFor(pageId: String): List<ElementEntity> =
        db.elementDao().listByPage(pageId).also { requireContiguous(it.map(ElementEntity::orderIndex)) }

    private fun requireSelected(rows: List<ElementEntity>, ids: List<String>): List<ElementEntity> {
        val byId = rows.associateBy { it.id }
        return ids.map { byId[it] ?: fail(LibraryErrorCode.NOT_FOUND, "Element not on specified page") }
    }

    private fun isLocked(row: ElementEntity): Boolean = JSONObject(row.elementJson).optBoolean("locked", false)

    private fun isPdfBackground(raw: String): Boolean = JSONObject(raw).optString("type") == "pdfPage"

    /** Check both representations: older or inconsistent rows must not bypass protection. */
    private fun isImageContent(row: ElementEntity): Boolean =
        row.kind == "image" || JSONObject(row.elementJson).optString("type") == "image"

    private suspend fun setPageOrder(rows: List<PageEntity>, ids: List<String>, revision: Long): Boolean {
        validatePermutation(rows.map { it.id }, ids)
        if (rows.map { it.id } == ids && rows.withIndex().all { it.value.orderIndex == it.index.toLong() }) {
            return false
        }
        val byId = rows.associateBy { it.id }
        val start = freeIndex(rows.map { it.orderIndex })
        ids.forEachIndexed { i, id ->
            check(db.pageDao().update(byId.getValue(id).copy(orderIndex = start + i, revision = revision)) == 1)
        }
        ids.forEachIndexed { i, id ->
            check(db.pageDao().update(byId.getValue(id).copy(orderIndex = i.toLong(), revision = revision)) == 1)
        }
        return true
    }

    private suspend fun setElementOrder(rows: List<ElementEntity>, ids: List<String>, revision: Long): Boolean {
        validatePermutation(rows.map { it.id }, ids)
        if (rows.map { it.id } == ids && rows.withIndex().all { it.value.orderIndex == it.index.toLong() }) {
            return false
        }
        val byId = rows.associateBy { it.id }
        val start = freeIndex(rows.map { it.orderIndex })
        ids.forEachIndexed { i, id ->
            check(db.elementDao().update(byId.getValue(id).copy(orderIndex = start + i, revision = revision)) == 1)
        }
        ids.forEachIndexed { i, id ->
            check(db.elementDao().update(byId.getValue(id).copy(orderIndex = i.toLong(), revision = revision)) == 1)
        }
        return true
    }

    /** All temporary positions are > every existing position in this sibling group. */
    private fun freeIndex(indexes: List<Long>): Long {
        val max = indexes.maxOrNull() ?: -1L
        if (max < 0L && indexes.isNotEmpty()) fail(LibraryErrorCode.INVALID_INPUT, "Invalid order index")
        if (Long.MAX_VALUE - max <= indexes.size.toLong()) {
            fail(LibraryErrorCode.INVALID_INPUT, "Ordering space exhausted")
        }
        return max + 1L
    }

    private fun requireContiguous(indexes: List<Long>) {
        if (indexes.withIndex().any { it.value != it.index.toLong() }) {
            fail(LibraryErrorCode.INVALID_INPUT, "Noncontiguous stored ordering")
        }
    }

    private fun validatePermutation(existing: List<String>, requested: List<String>) {
        requested.forEach(LibraryValidation::canonicalId)
        if (existing.size != requested.size || existing.toSet() != requested.toSet()) {
            fail(LibraryErrorCode.INVALID_INPUT, "Reorder must include each sibling exactly once")
        }
    }

    private fun insertionAt(at: Int?, size: Int): Int {
        val position = at ?: size
        if (position < 0 || position > size) fail(LibraryErrorCode.INVALID_INPUT, "Insert index is out of range")
        return position
    }

    private fun uniqueIds(ids: List<String>) {
        if (ids.size != ids.toSet().size) fail(LibraryErrorCode.INVALID_INPUT, "Duplicate element IDs")
    }

    private fun fail(code: LibraryErrorCode, message: String): Nothing =
        throw LibraryOperationException(code, message)
}
