package com.notebook.app.data.assets

import java.util.ArrayDeque
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * STO-08 C1A-1: diagnostic inspection of canonical page/element JSON.
 *
 * This is NOT the document write validator, a complete semantic validator, a
 * database ownership ledger, or authorization to delete any asset.
 * Unresolved results must cause future cleanup decisions to fail closed.
 */
internal object DocumentAssetReferenceInspector {
    private const val MAX_JSON_CHARS = 1_000_000
    private val canonicalUuid = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )
    private val rgb = Regex("^#[0-9a-fA-F]{6}$")
    private val envelopeKeys = setOf("id", "type", "transform", "locked", "payload")

    sealed interface Finding {
        data class Reference(val assetId: String) : Finding
        /** Only means no recognized direct reference in this inspected JSON. */
        data object NoDirectReference : Finding
        data class Unresolved(val reason: Reason) : Finding
    }

    enum class Reason {
        MALFORMED_OR_OVERSIZED_JSON,
        UNKNOWN_TYPE,
        INVALID_STRUCTURE,
        INVALID_ASSET_ID,
        POSSIBLE_HIDDEN_REFERENCE,
    }

    fun pageBackground(raw: String): Finding {
        val obj = parseObject(raw)
            ?: return Finding.Unresolved(Reason.MALFORMED_OR_OVERSIZED_JSON)
        return when (string(obj, "type")) {
            "blank" -> {
                if (obj.keys == setOf("type", "color") && validColor(obj["color"])) {
                    Finding.NoDirectReference
                } else Finding.Unresolved(Reason.INVALID_STRUCTURE)
            }
            "ruled", "grid", "dotted" -> {
                if (obj.keys == setOf("type", "color", "patternColor", "spacing") &&
                    validColor(obj["color"]) && validColor(obj["patternColor"]) &&
                    numeric(obj["spacing"])?.let { it > 0.0 } == true
                ) Finding.NoDirectReference else Finding.Unresolved(Reason.INVALID_STRUCTURE)
            }
            "pdfPage" -> {
                if (obj.keys != setOf("type", "assetId", "pageIndex", "displayBox") ||
                    integer(obj["pageIndex"])?.let { it >= 0 } != true ||
                    string(obj, "displayBox") !in setOf("crop", "media")
                ) return Finding.Unresolved(Reason.INVALID_STRUCTURE)
                val id = string(obj, "assetId")
                if (!isCanonicalUuid(id)) Finding.Unresolved(Reason.INVALID_ASSET_ID)
                else Finding.Reference(id!!)
            }
            else -> Finding.Unresolved(Reason.UNKNOWN_TYPE)
        }
    }

    fun element(raw: String): Finding {
        val obj = parseObject(raw)
            ?: return Finding.Unresolved(Reason.MALFORMED_OR_OVERSIZED_JSON)
        val type = string(obj, "type") ?: return Finding.Unresolved(Reason.UNKNOWN_TYPE)
        if (type !in setOf("stroke", "shape", "text", "image")) {
            return Finding.Unresolved(Reason.UNKNOWN_TYPE)
        }
        if (!validEnvelope(obj)) return Finding.Unresolved(Reason.INVALID_STRUCTURE)
        val payload = obj["payload"] as JsonObject
        if (type == "image") {
            if (!validImageShape(payload)) return Finding.Unresolved(Reason.INVALID_STRUCTURE)
            val id = string(payload, "assetId")
            if (!isCanonicalUuid(id)) return Finding.Unresolved(Reason.INVALID_ASSET_ID)
            return Finding.Reference(id!!)
        }
        val expected = when (type) {
            "stroke" -> setOf("encoding", "brush", "points")
            "shape" -> setOf("kind", "geometry", "stroke", "fill")
            else -> setOf("text", "frame", "style")
        }
        val allowed = if (type == "shape") expected + "arrowHead" else expected
        if (!payload.keys.containsAll(expected) || !allowed.containsAll(payload.keys)) {
            return Finding.Unresolved(Reason.INVALID_STRUCTURE)
        }
        if (hasNestedAssetId(payload)) {
            return Finding.Unresolved(Reason.POSSIBLE_HIDDEN_REFERENCE)
        }
        if (!validNonMediaShape(type, payload)) {
            return Finding.Unresolved(Reason.INVALID_STRUCTURE)
        }
        // Diagnostic only; semantic validation is still required before any GC.
        return Finding.NoDirectReference
    }

    private fun validNonMediaShape(type: String, payload: JsonObject): Boolean = when (type) {
        "stroke" -> string(payload, "encoding") == "sampledPointsV1" &&
            payload["brush"] is JsonObject &&
            (payload["points"] as? JsonArray)?.isNotEmpty() == true
        "shape" -> {
            val kind = string(payload, "kind")
            kind in setOf("line", "arrow", "rectangle", "ellipse") &&
                payload["geometry"] is JsonObject && payload["stroke"] is JsonObject &&
                (payload["fill"] is JsonObject || payload["fill"] == JsonNull) &&
                (kind != "arrow" || payload["arrowHead"] is JsonObject) &&
                (kind == "arrow" || "arrowHead" !in payload)
        }
        "text" -> string(payload, "text") != null &&
            (payload["frame"] as? JsonObject)?.let { validRectangle(it, normalized = false) } == true &&
            payload["style"] is JsonObject
        else -> false
    }

    private fun parseObject(raw: String): JsonObject? {
        if (raw.length > MAX_JSON_CHARS) return null
        return try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun validEnvelope(obj: JsonObject): Boolean {
        if (obj.keys != envelopeKeys || !isCanonicalUuid(string(obj, "id"))) return false
        val locked = obj["locked"] as? JsonPrimitive ?: return false
        if (locked.isString || locked.booleanOrNull == null) return false
        if (obj["payload"] !is JsonObject) return false
        val transform = obj["transform"] as? JsonArray ?: return false
        if (transform.size != 6) return false
        val values = transform.map { numeric(it) ?: return false }
        val det = values[0] * values[3] - values[1] * values[2]
        return det.isFinite() && det != 0.0
    }

    private fun validImageShape(obj: JsonObject): Boolean {
        if (!obj.keys.containsAll(setOf("assetId", "frame", "opacity")) ||
            !(setOf("assetId", "frame", "opacity", "crop")).containsAll(obj.keys)
        ) return false
        val frame = obj["frame"] as? JsonObject ?: return false
        if (!validRectangle(frame, normalized = false)) return false
        val opacity = numeric(obj["opacity"]) ?: return false
        if (opacity !in 0.0..1.0) return false
        if ("crop" in obj) {
            val crop = obj["crop"] as? JsonObject ?: return false
            if (!validRectangle(crop, normalized = true)) return false
        }
        return true
    }

    private fun validRectangle(obj: JsonObject, normalized: Boolean): Boolean {
        if (obj.keys != setOf("x", "y", "width", "height")) return false
        val x = numeric(obj["x"]) ?: return false
        val y = numeric(obj["y"]) ?: return false
        val width = numeric(obj["width"]) ?: return false
        val height = numeric(obj["height"]) ?: return false
        if (width <= 0.0 || height <= 0.0) return false
        return !normalized || (x >= 0.0 && y >= 0.0 && x + width <= 1.0 && y + height <= 1.0)
    }

    private fun hasNestedAssetId(root: JsonElement): Boolean {
        val pending = ArrayDeque<JsonElement>()
        pending.add(root)
        while (!pending.isEmpty()) {
            when (val current = pending.removeFirst()) {
                is JsonObject -> {
                    if ("assetId" in current) return true
                    current.values.forEach { pending.add(it) }
                }
                is JsonArray -> current.forEach { pending.add(it) }
                else -> Unit
            }
        }
        return false
    }

    private fun string(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun numeric(value: JsonElement?): Double? =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            ?.takeIf { it.isFinite() }

    private fun integer(value: JsonElement?): Int? =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private fun validColor(value: JsonElement?): Boolean =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(rgb::matches) == true

    private fun isCanonicalUuid(value: String?): Boolean {
        if (value == null || !canonicalUuid.matches(value)) return false
        return try {
            UUID.fromString(value).toString() == value
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
