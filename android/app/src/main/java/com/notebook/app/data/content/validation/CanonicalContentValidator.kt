package com.notebook.app.data.content.validation

import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.data.library.model.InitialPageSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** V0.2 editable JSON checks at the SQLite boundary; no native ink adapter involved.
 * All unknown fields are rejected to prevent writing content this app cannot preserve.
 * Asset-backed media is deliberately unsupported until the asset-reference lifecycle exists.
 */
internal object CanonicalContentValidator {
    private val hexColor = Regex("^#[0-9a-fA-F]{6}$")

    fun page(spec: InitialPageSpec) {
        LibraryValidation.initialPage(spec)
        background(spec.backgroundJson)
    }

    fun background(raw: String) {
        val o = parse(raw)
        when (string(o, "type")) {
            "blank" -> {
                keys(o, setOf("type", "color"))
                color(o, "color")
            }
            "ruled", "grid", "dotted" -> {
                keys(o, setOf("type", "color", "patternColor", "spacing"))
                color(o, "color")
                color(o, "patternColor")
                positive(o, "spacing")
            }
            "pdfPage" -> unsupported("PDF-backed pages require asset persistence")
            else -> invalid("Unrecognized page background type")
        }
    }

    /** Returns the redundant indexed kind, after validating its complete envelope. */
    fun element(raw: String): Pair<String, String> {
        val o = parse(raw)
        keys(o, setOf("id", "type", "transform", "locked", "payload"))
        val id = LibraryValidation.canonicalId(string(o, "id"))
        bool(o, "locked")
        val t = array(o, "transform")
        if (t.length() != 6) invalid("Transform must contain exactly six numbers")
        val n = (0 until 6).map { number(t.opt(it)) }
        val determinant = n[0] * n[3] - n[1] * n[2]
        if (!determinant.isFinite() || determinant == 0.0) invalid("Transform must be invertible")
        val p = obj(o, "payload")
        val kind = string(o, "type")
        when (kind) {
            "stroke" -> stroke(p)
            "shape" -> shape(p)
            "text" -> text(p)
            "image" -> unsupported("Image elements require asset persistence")
            else -> invalid("Unsupported element type")
        }
        return id to kind
    }

    private fun stroke(p: JSONObject) {
        keys(p, setOf("encoding", "brush", "points"))
        if (string(p, "encoding") != "sampledPointsV1") invalid("Unknown stroke encoding")
        val b = obj(p, "brush")
        keys(b, setOf("kind", "width", "color", "opacity"))
        choice(b, "kind", setOf("pen", "pencil", "highlighter"))
        positive(b, "width")
        color(b, "color")
        unit(b, "opacity")
        val points = array(p, "points")
        if (points.length() == 0) invalid("Stroke has no sampled points")
        var lastTime = -1L
        for (i in 0 until points.length()) {
            val pt = points.opt(i) as? JSONObject ?: invalid("Stroke point is not an object")
            keys(pt, setOf("x", "y", "tMs"), setOf("pressure", "tiltRad", "orientationRad"))
            number(pt.opt("x")); number(pt.opt("y"))
            val time = nonnegativeInteger(pt, "tMs")
            if (time < lastTime) invalid("Stroke timestamps must be nondecreasing")
            lastTime = time
            if (pt.has("pressure")) unit(pt, "pressure")
            if (pt.has("tiltRad") && number(pt.opt("tiltRad")) !in 0.0..(Math.PI / 2)) {
                invalid("Tilt out of range")
            }
            if (pt.has("orientationRad")) number(pt.opt("orientationRad"))
        }
    }

    private fun shape(p: JSONObject) {
        val kind = choice(p, "kind", setOf("line", "arrow", "rectangle", "ellipse"))
        val expected = setOf("kind", "geometry", "stroke", "fill")
        keys(p, if (kind == "arrow") expected + "arrowHead" else expected)
        val geom = obj(p, "geometry")
        if (kind == "line" || kind == "arrow") {
            val fields = setOf("x1", "y1", "x2", "y2")
            keys(geom, fields)
            fields.forEach { number(geom.opt(it)) }
        } else frame(geom)
        val s = obj(p, "stroke")
        keys(s, setOf("color", "width", "opacity"))
        color(s, "color"); positive(s, "width"); unit(s, "opacity")
        val fill = p.opt("fill")
        if (fill !== JSONObject.NULL) {
            val paint = fill as? JSONObject ?: invalid("Invalid shape fill")
            keys(paint, setOf("color", "opacity"))
            color(paint, "color"); unit(paint, "opacity")
        }
        if (kind == "arrow") {
            val a = obj(p, "arrowHead")
            keys(a, setOf("length", "angleDeg"))
            positive(a, "length"); number(a.opt("angleDeg"))
        }
    }

    private fun text(p: JSONObject) {
        keys(p, setOf("text", "frame", "style"))
        string(p, "text")
        frame(obj(p, "frame"))
        val s = obj(p, "style")
        keys(s, setOf("fontFamily", "fontSize", "color", "bold", "italic", "underline", "alignment"))
        choice(s, "fontFamily", setOf("sans", "serif", "mono"))
        positive(s, "fontSize"); color(s, "color")
        bool(s, "bold"); bool(s, "italic"); bool(s, "underline")
        choice(s, "alignment", setOf("left", "center", "right"))
    }

    private fun frame(o: JSONObject) {
        keys(o, setOf("x", "y", "width", "height"))
        number(o.opt("x")); number(o.opt("y"))
        positive(o, "width"); positive(o, "height")
    }

    private fun parse(raw: String): JSONObject = try {
        JSONObject(raw)
    } catch (_: JSONException) {
        invalid("Malformed canonical JSON object")
    }

    private fun obj(o: JSONObject, field: String): JSONObject =
        o.opt(field) as? JSONObject ?: invalid("Missing object: $field")

    private fun array(o: JSONObject, field: String): JSONArray =
        o.opt(field) as? JSONArray ?: invalid("Missing array: $field")

    private fun string(o: JSONObject, field: String): String =
        o.opt(field) as? String ?: invalid("Missing string: $field")

    private fun bool(o: JSONObject, field: String) {
        if (o.opt(field) !is Boolean) invalid("Missing Boolean: $field")
    }

    private fun number(value: Any?): Double {
        val n = (value as? Number)?.toDouble() ?: invalid("Expected number")
        if (!n.isFinite()) invalid("Non-finite numeric value")
        return n
    }

    private fun positive(o: JSONObject, field: String) {
        if (number(o.opt(field)) <= 0.0) invalid("$field must be positive")
    }

    private fun unit(o: JSONObject, field: String) {
        if (number(o.opt(field)) !in 0.0..1.0) invalid("$field must be in [0,1]")
    }

    private fun nonnegativeInteger(o: JSONObject, field: String): Long {
        val n = number(o.opt(field))
        if (n < 0.0 || n > 9_007_199_254_740_991.0 || n % 1.0 != 0.0) {
            invalid("$field must be a non-negative exact integer")
        }
        return n.toLong()
    }

    private fun color(o: JSONObject, field: String) {
        if (!hexColor.matches(string(o, field))) invalid("Invalid RGB color")
    }

    private fun choice(o: JSONObject, field: String, allowed: Set<String>): String {
        val v = string(o, field)
        if (v !in allowed) invalid("Unsupported $field value")
        return v
    }

    private fun keys(o: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val found = o.keys().asSequence().toSet()
        if (!found.containsAll(required) || !((required + optional).containsAll(found))) {
            invalid("Unknown or missing canonical JSON fields")
        }
    }

    private fun invalid(message: String): Nothing =
        throw LibraryOperationException(LibraryErrorCode.INVALID_INPUT, message)

    private fun unsupported(message: String): Nothing =
        throw LibraryOperationException(LibraryErrorCode.UNSUPPORTED_CONTENT, message)
}
