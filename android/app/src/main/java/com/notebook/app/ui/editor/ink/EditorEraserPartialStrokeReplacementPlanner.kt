package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * EDT-16 1E-3: read-only serialization and replacement planning.
 *
 * This is NOT a DocumentEdit, commit authorization, or undo stack. The current
 * repository cannot atomically replace one element with N differently-IDed
 * fragments. A later checkpoint must add a single revision-guarded command,
 * exact inverse, and conflict/rollback tests before these plans can be written.
 *
 * Geometry is computed against the same immutable note snapshot used to
 * capture original serialized envelopes and page order. If ANY stroke has
 * unresolved geometry, no plan or fragment IDs are emitted.
 */
internal object EditorEraserPartialStrokeReplacementPlanner {
    data class Replacement(
        val originalPosition: Int,
        val original: StoredElement,
        /** Zero fragments means the entire stroke was removed. */
        val fragmentEnvelopes: List<String>,
        val fragmentIds: List<String>,
    )

    data class Plan(
        val noteId: String,
        val pageId: String,
        val expectedNoteRevision: Long,
        val expectedPageRevision: Long,
        val originalElementIdsInOrder: List<String>,
        val resultingElementIdsInOrder: List<String>,
        val replacements: List<Replacement>,
        val forwardEdit: DocumentEdit.ApplyPartialStrokeReplacements,
        val inverseEdit: DocumentEdit.RestorePartialStrokeReplacements,
    )

    private data class Pending(
        val originalPosition: Int,
        val original: StoredElement,
        val pieces: List<List<StoredStrokeSample>>,
    )

    private const val MAX_TOTAL_FRAGMENTS = 8192
    private const val MAX_TOTAL_SAMPLES = 65536
    private const val MAX_EXACT_TIME = 9_007_199_254_740_991L

    /**
     * @return null only for a definite no-change sweep; throws on unsafe input.
     * [newElementId] is evaluated only after EVERY stroke has passed geometry
     * classification. It must provide fresh canonical UUIDs (no retries).
     */
    fun plan(
        content: StoredNoteContent,
        pageId: String,
        sweepPointsPageDu: List<Point>,
        sweepRadiusPageDu: Double,
        scanNoteRevision: Long = content.revision,
        scanPageRevision: Long? = null,
        newElementId: () -> String = { UUID.randomUUID().toString() },
    ): Plan? {
        require(content.noteId.isNotBlank() && pageId.isNotBlank())
        require(content.revision == scanNoteRevision && scanNoteRevision >= 0L) {
            "Stale partial-eraser note snapshot"
        }
        val page = requireNotNull(content.pages.singleOrNull { it.id == pageId }) {
            "Missing or ambiguous partial-eraser page"
        }
        require(scanPageRevision == null || page.revision == scanPageRevision) {
            "Stale partial-eraser page snapshot"
        }
        require(sweepPointsPageDu.isNotEmpty() && sweepPointsPageDu.size <= 8192)
        require(sweepRadiusPageDu.isFinite() && sweepRadiusPageDu >= 0.0)
        require(sweepPointsPageDu.all { it.x.isFinite() && it.y.isFinite() })
        require(page.width.isFinite() && page.width > 0.0 &&
            page.height.isFinite() && page.height > 0.0)

        val allIds = content.pages.flatMap { p -> p.elements.map { it.id } }
        require(allIds.size == allIds.toSet().size) { "Duplicate note-wide element IDs" }
        val ordered = page.elements.sortedBy { it.orderIndex }
        require(ordered.indices.all { ordered[it].orderIndex == it.toLong() }) {
            "Noncontiguous or duplicate page ordering"
        }
        val decoded = StoredCanonicalStrokeDecoder.fromPage(page)
        val strokesById = decoded.associateBy { it.elementId }
        require(strokesById.size == decoded.size)
        require(decoded.all { it.pageId == page.id })

        // Phase 1: run ALL geometry without allocating IDs or changing objects.
        val pending = ArrayList<Pending>()
        var fragmentCount = 0
        var sampleCount = 0
        ordered.forEachIndexed { position, element ->
            val stroke = strokesById[element.id] ?: return@forEachIndexed
            when (val result = EditorEraserPartialStrokeGeometry.split(
                stroke, page.width, page.height, sweepPointsPageDu, sweepRadiusPageDu,
            )) {
                EditorEraserPartialStrokeGeometry.Result.Unchanged -> Unit
                EditorEraserPartialStrokeGeometry.Result.Removed -> {
                    require(!stroke.locked) { "Locked stroke was selected" }
                    pending += Pending(position, element, emptyList())
                }
                is EditorEraserPartialStrokeGeometry.Result.Fragments -> {
                    require(!stroke.locked && result.pieces.isNotEmpty())
                    fragmentCount += result.pieces.size
                    require(fragmentCount <= MAX_TOTAL_FRAGMENTS) { "Too many new fragments" }
                    for (piece in result.pieces) {
                        require(piece.isNotEmpty())
                        sampleCount += piece.size
                        require(sampleCount <= MAX_TOTAL_SAMPLES) { "Too many output samples" }
                    }
                    pending += Pending(position, element, result.pieces)
                }
                is EditorEraserPartialStrokeGeometry.Result.Unresolved ->
                    throw IllegalArgumentException("Unresolved partial-eraser geometry: ${result.reason}")
            }
        }
        if (pending.isEmpty()) return null

        // Phase 2: serialize every cut as a new canonical stroke envelope. Keep
        // the original brush/transform/locked fields byte-semantically unchanged.
        val occupied = allIds.toMutableSet()
        val replacements = pending.map { candidate ->
            val originalJson = Json.parseToJsonElement(candidate.original.envelopeJson).let {
                require(it is JsonObject)
                it
            }
            require(originalJson["id"] == JsonPrimitive(candidate.original.id))
            require(originalJson["type"] == JsonPrimitive("stroke"))
            require(originalJson["locked"] == JsonPrimitive(false))
            val payload = originalJson["payload"] as? JsonObject
                ?: throw IllegalArgumentException("Missing canonical stroke payload")
            val generated = candidate.pieces.map { piece ->
                val id = UUID.fromString(newElementId()).toString()
                require(occupied.add(id)) { "Fragment ID collides with an existing element" }
                val points = JsonArray(piece.map(::encodeSample))
                val fragmentPayload = JsonObject(payload + ("points" to points))
                val fragment = JsonObject(originalJson + mapOf(
                    "id" to JsonPrimitive(id),
                    "payload" to fragmentPayload,
                )).toString()
                id to fragment
            }
            // Re-decode every candidate using the production decoder before
            // exposing it, including all optional sample metadata and affine.
            if (generated.isNotEmpty()) {
                val syntheticPage = page.copy(elements = generated.mapIndexed { index, (id, raw) ->
                    StoredElement(id, index.toLong(), "stroke", raw, candidate.original.revision)
                })
                val roundTrip = StoredCanonicalStrokeDecoder.fromPage(syntheticPage)
                require(roundTrip.size == generated.size &&
                    roundTrip.map { it.samples } == candidate.pieces) {
                    "Serialized fragment failed canonical round trip"
                }
                val source = strokesById.getValue(candidate.original.id)
                require(roundTrip.all {
                    it.brush == source.brush && it.elementTransform.coefficients() ==
                        source.elementTransform.coefficients() && !it.locked
                }) { "Fragment properties differ from source stroke" }
            }
            Replacement(candidate.originalPosition, candidate.original,
                generated.map { it.second }, generated.map { it.first })
        }
        val byPosition = replacements.associateBy { it.originalPosition }
        val resultingOrder = ordered.flatMapIndexed { index, element ->
            byPosition[index]?.fragmentIds ?: listOf(element.id)
        }
        require(resultingOrder.size == resultingOrder.toSet().size)
        val editReplacements = replacements.map { replacement ->
            DocumentEdit.PartialStrokeReplacement(
                replacement.original.envelopeJson,
                replacement.fragmentEnvelopes,
            )
        }
        val originalIds = ordered.map { it.id }
        return Plan(
            noteId = content.noteId,
            pageId = pageId,
            expectedNoteRevision = content.revision,
            expectedPageRevision = page.revision,
            originalElementIdsInOrder = originalIds,
            resultingElementIdsInOrder = resultingOrder,
            replacements = replacements,
            forwardEdit = DocumentEdit.ApplyPartialStrokeReplacements(
                page.id, originalIds, resultingOrder, editReplacements,
            ),
            inverseEdit = DocumentEdit.RestorePartialStrokeReplacements(
                page.id, originalIds, resultingOrder, editReplacements,
            ),
        )
    }

    private fun encodeSample(sample: StoredStrokeSample): JsonObject {
        require(sample.xDu.isFinite() && sample.yDu.isFinite())
        require(sample.tMs in 0L..MAX_EXACT_TIME)
        require(sample.pressure == null ||
            (sample.pressure.isFinite() && sample.pressure in 0.0..1.0))
        require(sample.tiltRad == null ||
            (sample.tiltRad.isFinite() && sample.tiltRad in 0.0..Math.PI / 2.0))
        require(sample.orientationRad == null || sample.orientationRad.isFinite())
        return JsonObject(buildMap {
            put("x", JsonPrimitive(sample.xDu))
            put("y", JsonPrimitive(sample.yDu))
            put("tMs", JsonPrimitive(sample.tMs))
            sample.pressure?.let { put("pressure", JsonPrimitive(it)) }
            sample.tiltRad?.let { put("tiltRad", JsonPrimitive(it)) }
            sample.orientationRad?.let { put("orientationRad", JsonPrimitive(it)) }
        })
    }
}
