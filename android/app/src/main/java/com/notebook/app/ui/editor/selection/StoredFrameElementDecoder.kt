package com.notebook.app.ui.editor.selection

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.ui.editor.ink.CanonicalElementAffine
import kotlinx.serialization.json.*

internal data class CanonicalFrame(val x: Double, val y: Double, val width: Double, val height: Double)
internal data class WholeBoxTextStyle(
    val fontFamily: String, val fontSize: Double, val color: String,
    val bold: Boolean, val italic: Boolean, val underline: Boolean, val alignment: String,
)

/** Derived only. Exact canonical bytes stay in source; no rendering or mutation authority. */
internal sealed class StoredFrameElement(
    val pageId: String, val source: StoredElement, val locked: Boolean,
    val transform: CanonicalElementAffine, val frame: CanonicalFrame,
) {
    /** Parse on demand so mutable JSON maps cannot become authoritative adapter state. */
    val payload: JsonObject get() = Json.parseToJsonElement(source.envelopeJson).jsonObject
        .getValue("payload").jsonObject

    class Text internal constructor(
        pageId: String, source: StoredElement, locked: Boolean, transform: CanonicalElementAffine,
        frame: CanonicalFrame, val text: String, val style: WholeBoxTextStyle,
    ) : StoredFrameElement(pageId, source, locked, transform, frame)

    class Image internal constructor(
        pageId: String, source: StoredElement, locked: Boolean, transform: CanonicalElementAffine,
        frame: CanonicalFrame, val assetId: String, val opacity: Double, val crop: CanonicalFrame?,
    ) : StoredFrameElement(pageId, source, locked, transform, frame) {
        // Structural JSON validity never establishes bytes, ownership or rendering availability.
        val availability: ImageAvailability get() = ImageAvailability.UNVERIFIED
    }
}
internal enum class ImageAvailability { UNVERIFIED }

internal object StoredFrameElementDecoder {
    fun decode(pageId: String, source: StoredElement): StoredFrameElement {
        LibraryValidation.canonicalId(pageId)
        val envelope = checkedEnvelope(source)
        require(source.kind == "text" || source.kind == "image") { "Not a frame element" }
        val locked = envelope.flag("locked")
        val transform = affine(envelope)
        val payload = envelope.getValue("payload").jsonObject
        val frame = frame(payload.getValue("frame"))
        return if (source.kind == "text") {
            payload.fields(setOf("text", "frame", "style"))
            val style = payload.getValue("style").jsonObject
            style.fields(setOf("fontFamily", "fontSize", "color", "bold", "italic", "underline", "alignment"))
            val family = style.string("fontFamily")
            require(family in setOf("sans", "serif", "mono"))
            val size = style.number("fontSize")
            require(size > 0)
            val color = style.string("color")
            require(Regex("^#[0-9a-fA-F]{6}$").matches(color))
            val alignment = style.string("alignment")
            require(alignment in setOf("left", "center", "right"))
            StoredFrameElement.Text(pageId, source, locked, transform, frame, payload.string("text"),
                WholeBoxTextStyle(family, size, color, style.flag("bold"), style.flag("italic"),
                    style.flag("underline"), alignment))
        } else {
            payload.fields(setOf("assetId", "frame", "opacity"), setOf("crop"))
            val asset = payload.string("assetId")
            LibraryValidation.canonicalId(asset)
            val opacity = payload.number("opacity")
            require(opacity in 0.0..1.0)
            val crop = payload["crop"]?.let(::frame)
            if (crop != null) require(crop.x >= 0 && crop.y >= 0 &&
                crop.width <= 1 && crop.height <= 1 &&
                crop.x + crop.width <= 1 && crop.y + crop.height <= 1) { "Invalid normalized crop" }
            StoredFrameElement.Image(pageId, source, locked, transform, frame, asset, opacity, crop)
        }
    }

    /** Common envelope checks only; does not validate stroke/shape payloads or authorize edits. */
    internal fun checkedEnvelope(source: StoredElement): JsonObject {
        LibraryValidation.canonicalId(source.id)
        require(source.orderIndex >= 0 && source.revision >= 0)
        require(source.kind in setOf("stroke", "shape", "text", "image"))
        val o = Json.parseToJsonElement(source.envelopeJson).jsonObject
        o.fields(setOf("id", "type", "locked", "transform", "payload"))
        require(o.string("id") == source.id && o.string("type") == source.kind) { "Indexed identity mismatch" }
        o.flag("locked")
        affine(o)
        o.getValue("payload").jsonObject
        return o
    }

    private fun affine(o: JsonObject): CanonicalElementAffine =
        requireNotNull(CanonicalElementAffine.fromCanonicalOrNull(
            o.getValue("transform").jsonArray.map(::number))) { "Invalid affine" }

    private fun frame(raw: JsonElement): CanonicalFrame {
        val o = raw.jsonObject
        o.fields(setOf("x", "y", "width", "height"))
        val result = CanonicalFrame(o.number("x"), o.number("y"), o.number("width"), o.number("height"))
        require(result.width > 0 && result.height > 0)
        return result
    }

    private fun JsonObject.fields(required: Set<String>, optional: Set<String> = emptySet()) {
        require(keys.containsAll(required) && keys.all { it in required || it in optional }) {
            "Missing or unsupported fields"
        }
    }
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let {
        require(it.isString) { "Expected string: $key" }; it.content
    }
    internal fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.let {
        require(!it.isString)
        requireNotNull(it.booleanOrNull) { "Expected Boolean: $key" }
    }
    private fun JsonObject.number(key: String): Double = number(getValue(key))
    private fun number(raw: JsonElement): Double = raw.jsonPrimitive.let {
        require(!it.isString)
        requireNotNull(it.doubleOrNull?.takeIf(Double::isFinite)) { "Expected finite number" }
    }
}
