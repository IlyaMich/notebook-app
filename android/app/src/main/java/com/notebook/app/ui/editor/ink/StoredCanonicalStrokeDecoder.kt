
package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.viewport.DuPoint
import kotlinx.serialization.json.*

/**
 * Read-only stroke data reconstructed from canonical Room JSON.
 *
 * No native Ink objects, Compose types, or database writes.
 * Coordinates and brush width remain in document units.
 */
internal data class StoredStrokeSample(
    val xDu: Double,
    val yDu: Double,
    val tMs: Long,
    val pressure: Double?,
    val tiltRad: Double?,
    val orientationRad: Double?,
) {
    val localPoint: DuPoint
        get() = DuPoint(xDu, yDu)
}

internal data class StoredStrokeBrush(
    val kind: String,
    val widthDu: Double,
    val colorHex: String,
    val opacity: Double,
)

internal data class StoredCanonicalStroke(
    val pageId: String,
    val elementId: String,
    val orderIndex: Long,
    val locked: Boolean,
    val elementTransform: CanonicalElementAffine,
    val brush: StoredStrokeBrush,
    val samples: List<StoredStrokeSample>,
)

/**
 * EDT-07: Decode strokes from an already validated
 * DocumentRepository.load() snapshot.
 *
 * This is not a general-purpose document importer.
 * Non-stroke elements remain owned by their other adapters.
 */
internal object StoredCanonicalStrokeDecoder {

    private val hexColor = Regex("^#[0-9a-fA-F]{6}$")
    private const val MAX_EXACT_TIME = 9_007_199_254_740_991.0

    fun fromPage(page: StoredPage): List<StoredCanonicalStroke> =
        page.elements.mapNotNull { element ->
            when (element.kind) {
                "stroke" -> decode(page.id, element)
                "shape", "text", "image" -> null
                else -> throw IllegalArgumentException(
                    "Unsupported stored element kind: ${element.kind}"
                )
            }
        }

    private fun decode(
        pageId: String,
        element: StoredElement,
    ): StoredCanonicalStroke {
        val envelope = Json.parseToJsonElement(
            element.envelopeJson
        ).jsonObject

        envelope.fields(
            required = setOf(
                "id", "type", "transform", "locked", "payload"
            )
        )

        val id = envelope.string("id")
        require(id == element.id) {
            "Stored element identity differs from envelope"
        }
        require(envelope.string("type") == "stroke") {
            "Stored element kind differs from envelope"
        }

        val transformValues = envelope.getValue("transform")
            .jsonArray.map(::finiteNumber)

        val transform = requireNotNull(
            CanonicalElementAffine.fromCanonicalOrNull(
                transformValues
            )
        ) {
            "Invalid canonical element transform"
        }

        val locked = envelope.boolean("locked")
        val payload = envelope.getValue("payload").jsonObject

        payload.fields(
            required = setOf("encoding", "brush", "points")
        )
        require(payload.string("encoding") == "sampledPointsV1") {
            "Unsupported stroke encoding"
        }

        val brushObject = payload.getValue("brush").jsonObject
        brushObject.fields(
            required = setOf(
                "kind", "width", "color", "opacity"
            )
        )

        val kind = brushObject.string("kind")
        require(kind in setOf("pen", "pencil", "highlighter"))

        val width = finiteNumber(brushObject.getValue("width"))
        require(width > 0.0)

        val color = brushObject.string("color")
        require(hexColor.matches(color))

        val opacity = unitNumber(
            brushObject.getValue("opacity")
        )

        val brush = StoredStrokeBrush(
            kind = kind,
            widthDu = width,
            colorHex = color,
            opacity = opacity,
        )

        val rawPoints = payload.getValue("points").jsonArray
        require(rawPoints.isNotEmpty()) {
            "Canonical stroke requires at least one sample"
        }

        var previousTime = -1L

        val samples = rawPoints.map { raw ->
            val point = raw.jsonObject
            point.fields(
                required = setOf("x", "y", "tMs"),
                optional = setOf(
                    "pressure", "tiltRad", "orientationRad"
                ),
            )

            val timeNumber = finiteNumber(
                point.getValue("tMs")
            )
            require(
                timeNumber >= 0.0 &&
                    timeNumber <= MAX_EXACT_TIME &&
                    timeNumber % 1.0 == 0.0
            ) {
                "Invalid canonical timestamp"
            }

            val time = timeNumber.toLong()
            require(time >= previousTime) {
                "Stroke timestamps must be nondecreasing"
            }
            previousTime = time

            val pressure = point["pressure"]?.let(::unitNumber)
            val tilt = point["tiltRad"]?.let(::finiteNumber)
            if (tilt != null) {
                require(tilt in 0.0..(Math.PI / 2.0))
            }

            StoredStrokeSample(
                xDu = finiteNumber(point.getValue("x")),
                yDu = finiteNumber(point.getValue("y")),
                tMs = time,
                pressure = pressure,
                tiltRad = tilt,
                orientationRad = point["orientationRad"]
                    ?.let(::finiteNumber),
            )
        }

        return StoredCanonicalStroke(
            pageId = pageId,
            elementId = id,
            orderIndex = element.orderIndex,
            locked = locked,
            elementTransform = transform,
            brush = brush,
            samples = samples,
        )
    }

    private fun JsonObject.fields(
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        require(keys.containsAll(required)) {
            "Missing canonical fields"
        }
        require(keys.all { it in required || it in optional }) {
            "Unsupported canonical fields"
        }
    }

    private fun JsonObject.string(field: String): String {
        val value = getValue(field).jsonPrimitive
        require(value.isString) {
            "Expected string: $field"
        }
        return value.content
    }

    private fun JsonObject.boolean(field: String): Boolean {
        val value = getValue(field).jsonPrimitive
        require(!value.isString) {
            "Expected Boolean: $field"
        }
        return value.boolean
    }

    private fun finiteNumber(value: JsonElement): Double {
        val primitive = value.jsonPrimitive
        require(!primitive.isString) {
            "Expected numeric JSON value"
        }
        val number = primitive.double
        require(number.isFinite()) {
            "Non-finite canonical number"
        }
        return number
    }

    private fun unitNumber(value: JsonElement): Double =
        finiteNumber(value).also {
            require(it in 0.0..1.0) {
                "Expected numeric value in [0,1]"
            }
        }
}
