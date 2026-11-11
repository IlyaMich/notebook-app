package com.notebook.app.data.content.model

import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.LibraryValidation

/**
 * C2C-3C-I2A: typed inputs for the future insert-only image owner transaction.
 * Not a DocumentEdit: generic image insertion stays rejected.
 * No caller-supplied media JSON, MIME, path, hash, crop, or lock state.
 * Validation does not prove the asset exists or authorize attachment.
 */
internal data class InsertVerifiedImageCommand(
    val noteId: String,
    val pageId: String,
    val elementId: String,
    val assetId: String,
    val expectedNoteRevision: Long,
    val at: Int? = null,
    val transform: List<Double>,
    val frame: ImageFrame,
    val opacity: Double,
) {
    /** Returns a defensive snapshot to carry across suspending verification. */
    fun validated(): ValidatedInsertVerifiedImage {
        val note = LibraryValidation.canonicalId(noteId)
        val page = LibraryValidation.canonicalId(pageId)
        val element = LibraryValidation.canonicalId(elementId)
        val asset = LibraryValidation.canonicalId(assetId)
        if (expectedNoteRevision < 0L) invalid("Negative expected note revision")
        if (at != null && at < 0) invalid("Negative image insertion position")
        if (transform.size != 6 || transform.any { !it.isFinite() }) {
            invalid("Image transform must contain six finite values")
        }
        val determinant = transform[0] * transform[3] - transform[1] * transform[2]
        if (!determinant.isFinite() || determinant == 0.0) {
            invalid("Image transform must be invertible")
        }
        if (!frame.x.isFinite() || !frame.y.isFinite() ||
            !frame.width.isFinite() || !frame.height.isFinite() ||
            frame.width <= 0.0 || frame.height <= 0.0
        ) invalid("Image frame must contain finite coordinates and positive size")
        if (!opacity.isFinite() || opacity < 0.0 || opacity > 1.0) {
            invalid("Image opacity must be between zero and one")
        }
        return ValidatedInsertVerifiedImage(
            noteId = note,
            pageId = page,
            elementId = element,
            assetId = asset,
            expectedNoteRevision = expectedNoteRevision,
            at = at,
            transform = transform.toList(),
            frame = frame.copy(),
            opacity = opacity,
        )
    }
}

internal data class ImageFrame(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/** Input-only snapshot: still requires lease, decode, Room and ownership checks. */
internal data class ValidatedInsertVerifiedImage(
    val noteId: String,
    val pageId: String,
    val elementId: String,
    val assetId: String,
    val expectedNoteRevision: Long,
    val at: Int?,
    val transform: List<Double>,
    val frame: ImageFrame,
    val opacity: Double,
)

private fun invalid(message: String): Nothing =
    throw LibraryOperationException(LibraryErrorCode.INVALID_INPUT, message)
