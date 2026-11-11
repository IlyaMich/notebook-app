package com.notebook.app.ui.editor.shapes

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.ui.editor.ink.CanonicalElementAffine
import kotlinx.serialization.json.*

private val shapeColor = Regex("^#[0-9a-fA-F]{6}$")

internal data class CanonicalShapeStroke(val colorHex: String, val widthDu: Double, val opacity: Double) {
    init {
        require(shapeColor.matches(colorHex)) { "Invalid shape stroke color" }
        require(widthDu.isFinite() && widthDu > 0.0) { "Invalid shape stroke width" }
        require(opacity.isFinite() && opacity in 0.0..1.0) { "Invalid shape stroke opacity" }
    }
}

internal data class CanonicalShapeFill(val colorHex: String, val opacity: Double) {
    init {
        require(shapeColor.matches(colorHex)) { "Invalid shape fill color" }
        require(opacity.isFinite() && opacity in 0.0..1.0) { "Invalid shape fill opacity" }
    }
}

internal data class CanonicalShapeArrowHead(val lengthDu: Double, val angleDeg: Double) {
    init {
        require(lengthDu.isFinite() && lengthDu > 0.0) { "Invalid arrow head length" }
        require(angleDeg.isFinite()) { "Invalid arrow head angle" }
    }
}

/** Read-only adapter view of one existing page-owned envelope, including its exact source bytes. */
internal class StoredCanonicalShape internal constructor(
    val pageId: String,
    val source: StoredElement,
    val kind: CanonicalShapeKind,
    val geometry: CanonicalShapeGeometry,
    val stroke: CanonicalShapeStroke,
    val fill: CanonicalShapeFill?,
    val arrowHead: CanonicalShapeArrowHead?,
    val elementTransform: CanonicalElementAffine,
    val locked: Boolean,
)

/**
 * EDT-12 C1: existing version-1 shape envelope adapter, not an importer or persistence service.
 * No rendering/arrow interpretation, command submission, page transfer or viewport state.
 */
internal object CanonicalShapeAdapter {
    fun decode(pageId: String, element: StoredElement): StoredCanonicalShape {
        LibraryValidation.canonicalId(pageId)
        LibraryValidation.canonicalId(element.id)
        require(element.kind == "shape") { "Not a stored shape" }
        require(element.orderIndex >= 0 && element.revision >= 0) { "Invalid stored shape metadata" }
        val envelope = Json.parseToJsonElement(element.envelopeJson).jsonObject
        envelope.fields("id", "type", "transform", "locked", "payload")
        require(envelope.string("id") == element.id) { "Shape identity differs from its envelope" }
        require(envelope.string("type") == element.kind) { "Shape kind differs from its envelope" }
        val affine = requireNotNull(CanonicalElementAffine.fromCanonicalOrNull(
            envelope.getValue("transform").jsonArray.map(::number),
        )) { "Invalid shape affine transform" }
        val lockedValue = envelope.getValue("locked").jsonPrimitive
        require(!lockedValue.isString) { "Expected Boolean locked" }
        val locked = requireNotNull(lockedValue.booleanOrNull) { "Expected Boolean locked" }
        val payload = envelope.getValue("payload").jsonObject
        val kind = requireNotNull(CanonicalShapeKind.entries.singleOrNull {
            it.wireName == payload.string("kind")
        }) { "Unsupported shape kind" }
        if (kind == CanonicalShapeKind.ARROW) {
            payload.fields("kind", "geometry", "stroke", "fill", "arrowHead")
        } else {
            payload.fields("kind", "geometry", "stroke", "fill")
        }
        val g = payload.getValue("geometry").jsonObject
        val geometry = when (kind) {
            CanonicalShapeKind.LINE, CanonicalShapeKind.ARROW -> {
                g.fields("x1", "y1", "x2", "y2")
                CanonicalShapeGeometry.Endpoints(g.number("x1"), g.number("y1"),
                    g.number("x2"), g.number("y2"))
            }
            CanonicalShapeKind.RECTANGLE, CanonicalShapeKind.ELLIPSE -> {
                g.fields("x", "y", "width", "height")
                CanonicalShapeGeometry.Frame(g.number("x"), g.number("y"),
                    g.number("width"), g.number("height"))
            }
        }
        val s = payload.getValue("stroke").jsonObject
        s.fields("color", "width", "opacity")
        val stroke = CanonicalShapeStroke(s.string("color"), s.number("width"), s.number("opacity"))
        val fill = payload.getValue("fill").let { raw ->
            if (raw == JsonNull) null else raw.jsonObject.let {
                it.fields("color", "opacity")
                CanonicalShapeFill(it.string("color"), it.number("opacity"))
            }
        }
        val head = if (kind == CanonicalShapeKind.ARROW) {
            payload.getValue("arrowHead").jsonObject.let {
                it.fields("length", "angleDeg")
                CanonicalShapeArrowHead(it.number("length"), it.number("angleDeg"))
            }
        } else null
        return StoredCanonicalShape(pageId, element, kind, geometry, stroke, fill, head, affine, locked)
    }

    /** New canonical envelope only; the containing page/repository determines ownership and order. */
    fun create(
        elementId: String,
        kind: CanonicalShapeKind,
        geometry: CanonicalShapeGeometry,
        stroke: CanonicalShapeStroke,
        fill: CanonicalShapeFill? = null,
        arrowHead: CanonicalShapeArrowHead? = null,
    ): String {
        LibraryValidation.canonicalId(elementId)
        requirePayload(kind, geometry, arrowHead)
        return buildJsonObject {
            put("id", elementId)
            put("type", "shape")
            put("transform", buildJsonArray { listOf(1, 0, 0, 1, 0, 0).forEach { add(it) } })
            put("locked", false)
            put("payload", buildJsonObject {
                put("kind", kind.wireName)
                put("geometry", encode(geometry))
                put("stroke", encode(stroke))
                put("fill", encode(fill))
                if (arrowHead != null) put("arrowHead", encode(arrowHead))
            })
        }.toString()
    }

    /**
     * Produce a same-ID replacement. Unchanged fields retain their original JSON values;
     * a no-op returns the exact original string. Callers retain source for an exact inverse.
     * Lock, identity, page and ordering are preserved. Movement may supply a validated affine.
     */
    fun replace(
        shape: StoredCanonicalShape,
        geometry: CanonicalShapeGeometry = shape.geometry,
        stroke: CanonicalShapeStroke = shape.stroke,
        fill: CanonicalShapeFill? = shape.fill,
        arrowHead: CanonicalShapeArrowHead? = shape.arrowHead,
        elementTransform: CanonicalElementAffine = shape.elementTransform,
    ): String {
        require(!shape.locked) { "Locked shape cannot be edited" }
        requirePayload(shape.kind, geometry, arrowHead)
        if (geometry == shape.geometry && stroke == shape.stroke &&
            fill == shape.fill && arrowHead == shape.arrowHead &&
            elementTransform.coefficients() == shape.elementTransform.coefficients()) return shape.source.envelopeJson
        val envelope = Json.parseToJsonElement(shape.source.envelopeJson).jsonObject
        val fields = envelope.getValue("payload").jsonObject.toMutableMap()
        if (geometry != shape.geometry) fields["geometry"] = encode(geometry)
        if (stroke != shape.stroke) fields["stroke"] = encode(stroke)
        if (fill != shape.fill) fields["fill"] = encode(fill)
        if (arrowHead != shape.arrowHead && arrowHead != null) fields["arrowHead"] = encode(arrowHead)
        val result = envelope.toMutableMap()
        result["payload"] = JsonObject(fields)
        if (elementTransform.coefficients() != shape.elementTransform.coefficients()) {
            result["transform"] = JsonArray(elementTransform.coefficients().map(::JsonPrimitive))
        }
        return JsonObject(result).toString()
    }

    private fun requirePayload(
        kind: CanonicalShapeKind, geometry: CanonicalShapeGeometry, head: CanonicalShapeArrowHead?,
    ) {
        require(kind.accepts(geometry)) { "Shape geometry does not match kind" }
        require((kind == CanonicalShapeKind.ARROW) == (head != null)) {
            "Only arrows require an arrow head"
        }
    }

    private fun encode(g: CanonicalShapeGeometry): JsonObject = buildJsonObject {
        when (g) {
            is CanonicalShapeGeometry.Endpoints -> {
                put("x1", g.x1); put("y1", g.y1); put("x2", g.x2); put("y2", g.y2)
            }
            is CanonicalShapeGeometry.Frame -> {
                put("x", g.x); put("y", g.y); put("width", g.width); put("height", g.height)
            }
        }
    }
    private fun encode(s: CanonicalShapeStroke): JsonObject = buildJsonObject {
        put("color", s.colorHex); put("width", s.widthDu); put("opacity", s.opacity)
    }
    private fun encode(f: CanonicalShapeFill?): JsonElement = if (f == null) JsonNull else buildJsonObject {
        put("color", f.colorHex); put("opacity", f.opacity)
    }
    private fun encode(h: CanonicalShapeArrowHead): JsonObject = buildJsonObject {
        put("length", h.lengthDu); put("angleDeg", h.angleDeg)
    }

    private fun JsonObject.fields(vararg expected: String) {
        require(keys == expected.toSet()) { "Missing or unsupported canonical shape fields" }
    }
    private fun JsonObject.string(key: String): String {
        val value = getValue(key).jsonPrimitive
        require(value.isString) { "Expected string: $key" }
        return value.content
    }
    private fun JsonObject.number(key: String): Double = number(getValue(key))
    private fun number(raw: JsonElement): Double {
        val primitive = raw.jsonPrimitive
        require(!primitive.isString) { "Expected numeric JSON value" }
        return requireNotNull(primitive.doubleOrNull?.takeIf { it.isFinite() }) {
            "Expected finite canonical number"
        }
    }
}
