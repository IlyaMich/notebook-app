package com.notebook.app.ui.editor.ink

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * EDT-05 CP2A: debug-only adapter from the CP1B canonical stroke DTO to FMT-04's
 * existing persisted stroke envelope. This codec never stores native Ink objects.
 * It is intentionally independent of Compose, viewport state and Room internals.
 */
internal object CanonicalInkDocumentCodec {
    private val identity = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    fun encode(elementId: String, stroke: CanonicalInkStroke): String {
        UUID.fromString(elementId)
        val brush = JSONObject()
            .put("kind", stroke.brush.kind)
            .put("width", stroke.brush.widthDu)
            .put("color", stroke.brush.colorHex)
            .put("opacity", stroke.brush.opacity)

        val points = JSONArray()
        stroke.points.forEach { sample ->
            val point = JSONObject()
                .put("x", sample.x)
                .put("y", sample.y)
                .put("tMs", sample.tMs)
            sample.pressure?.let { point.put("pressure", it) }
            sample.tiltRad?.let { point.put("tiltRad", it) }
            sample.orientationRad?.let { point.put("orientationRad", it) }
            points.put(point)
        }
        val payload = JSONObject()
            .put("encoding", "sampledPointsV1")
            .put("brush", brush)
            .put("points", points)
        return JSONObject()
            .put("id", elementId)
            .put("type", "stroke")
            .put("transform", JSONArray(identity))
            .put("locked", false)
            .put("payload", payload)
            .toString()
    }

    /** Read-only projection: it must never overwrite its source JSON on loading. */
    fun decode(envelopeJson: String): StoredCanonicalInkStroke {
        val envelope = JSONObject(envelopeJson)
        require(envelope.getString("type") == "stroke") { "Not a stroke envelope" }
        val id = envelope.getString("id")
        UUID.fromString(id)
        val transform = envelope.getJSONArray("transform")
        require(transform.length() == identity.size && identity.indices.all {
            transform.getDouble(it) == identity[it]
        }) { "Non-identity stroke transforms are outside CP2A's preview scope" }
        val locked = envelope.getBoolean("locked")
        val payload = envelope.getJSONObject("payload")
        require(payload.getString("encoding") == "sampledPointsV1") {
            "Unsupported required stroke encoding"
        }
        val brush = payload.getJSONObject("brush")
        val points = payload.getJSONArray("points")
        val resultPoints = (0 until points.length()).map { index ->
            val point = points.getJSONObject(index)
            // Don't coerce fractional timestamps to Long or silently change sample timing.
            val timeNumber = point.get("tMs") as? Number
                ?: throw IllegalArgumentException("tMs must be an integer number")
            val time = timeNumber.toLong()
            require(timeNumber.toDouble() == time.toDouble()) { "tMs must be an integer" }
            CanonicalInkPoint(
                x = point.getDouble("x"),
                y = point.getDouble("y"),
                tMs = time,
                pressure = point.optionalDouble("pressure"),
                tiltRad = point.optionalDouble("tiltRad"),
                orientationRad = point.optionalDouble("orientationRad"),
            )
        }
        val stroke = CanonicalInkStroke(
            brush = CanonicalInkBrush(
                kind = brush.getString("kind"),
                widthDu = brush.getDouble("width"),
                colorHex = brush.getString("color"),
                opacity = brush.getDouble("opacity"),
            ),
            points = resultPoints,
        )
        return StoredCanonicalInkStroke(id, stroke, locked)
    }

    private fun JSONObject.optionalDouble(key: String): Double? =
        if (has(key)) getDouble(key) else null
}

internal data class StoredCanonicalInkStroke(
    val elementId: String,
    val stroke: CanonicalInkStroke,
    val locked: Boolean,
)
