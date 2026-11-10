package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class EditorEraserPartialStrokeReplacementPlannerTest {
    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"
    private val third = "33333333-3333-4333-8333-333333333333"
    private val other = "44444444-4444-4444-8444-444444444444"

    private fun envelope(
        id: String,
        locked: Boolean = false,
        points: String = """[{"x":10.0,"y":100.0,"tMs":0,"pressure":0.2,"tiltRad":0.1,"orientationRad":-1.0},{"x":90.0,"y":100.0,"tMs":160,"pressure":0.8,"tiltRad":0.5,"orientationRad":1.0}]""",
        transform: String = "[1,0,0,1,0,0]",
    ) = """{"id":"$id","type":"stroke","transform":$transform,"locked":$locked,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pencil","width":2.0,"color":"#112233","opacity":0.85},"points":$points}}"""

    private fun row(id: String, order: Long, raw: String = envelope(id)) =
        StoredElement(id, order, "stroke", raw, 7L)

    private fun note(rows: List<StoredElement> = listOf(row(first, 0))) = StoredNoteContent(
        noteId = "note-one", revision = 7L, pages = listOf(
            StoredPage("page-one", 0L, 600.0, 800.0, """{"type":"blank","color":"#FFFFFF"}""", 2L, rows),
        ),
    )

    private fun freshIds(): () -> String {
        var next = 10L
        return { UUID(0L, next++).toString() }
    }

    private fun plan(
        content: StoredNoteContent = note(),
        sweep: List<Point> = listOf(Point(50.0, 100.0)),
        radius: Double = 4.0,
        generator: () -> String = freshIds(),
    ) = EditorEraserPartialStrokeReplacementPlanner.plan(
        content, "page-one", sweep, radius,
        scanNoteRevision = 7L, scanPageRevision = 2L,
        newElementId = generator,
    )

    @Test fun splitsOneStrokeIntoNewCanonicalIdsAndRoundTrips() {
        val output = requireNotNull(plan())
        assertEquals(listOf(first), output.originalElementIdsInOrder)
        assertEquals(1, output.replacements.size)
        val replacement = output.replacements.single()
        assertEquals(first, replacement.original.id)
        assertEquals(envelope(first), replacement.original.envelopeJson)
        assertEquals(2, replacement.fragmentIds.size)
        assertEquals(replacement.fragmentIds, output.resultingElementIdsInOrder)
        assertTrue(replacement.fragmentIds.all { it != first })
        replacement.fragmentEnvelopes.forEachIndexed { index, raw ->
            val json = Json.parseToJsonElement(raw).jsonObject
            assertEquals(replacement.fragmentIds[index], json.getValue("id").jsonPrimitive.content)
            assertEquals("pencil", json.getValue("payload").jsonObject.getValue("brush").jsonObject.getValue("kind").jsonPrimitive.content)
            assertEquals("[1,0,0,1,0,0]", json.getValue("transform").toString())
        }
        val page = note().pages.single().copy(elements = replacement.fragmentEnvelopes.mapIndexed { idx, raw ->
            row(replacement.fragmentIds[idx], idx.toLong(), raw)
        })
        val decoded = StoredCanonicalStrokeDecoder.fromPage(page)
        assertEquals(2, decoded.size)
        assertEquals(45.0, decoded[0].samples.last().xDu, 1e-7)
        assertEquals(55.0, decoded[1].samples.first().xDu, 1e-7)
        assertEquals(70L, decoded[0].samples.last().tMs)
        assertEquals(90L, decoded[1].samples.first().tMs)
        assertEquals(0.4625, decoded[0].samples.last().pressure!!, 1e-7)
        assertEquals(0.5375, decoded[1].samples.first().pressure!!, 1e-7)
    }

    @Test fun untouchedAndLockedRowsPreserveOrderAndOriginalEnvelopes() {
        val locked = row(second, 1, envelope(second, locked = true))
        val content = note(listOf(row(first, 0), locked, row(third, 2,
            envelope(third, points = """[{"x":300,"y":300,"tMs":0}]"""))))
        val result = requireNotNull(plan(content))
        assertEquals(1, result.replacements.size)
        assertEquals(listOf(first, second, third), result.originalElementIdsInOrder)
        assertEquals(listOf(result.replacements.single().fragmentIds[0],
            result.replacements.single().fragmentIds[1], second, third), result.resultingElementIdsInOrder)
        assertEquals(envelope(second, locked = true), locked.envelopeJson)
    }

    @Test fun fullRemovalHasNoNewIds() {
        var generated = 0
        val result = requireNotNull(plan(radius = 100.0, generator = {
            generated++
            UUID.randomUUID().toString()
        }))
        assertEquals(0, generated)
        assertEquals(emptyList<String>(), result.resultingElementIdsInOrder)
        assertTrue(result.replacements.single().fragmentEnvelopes.isEmpty())
    }

    @Test fun definiteNoHitHasNoPlanAndDoesNotConsumeIds() {
        var generated = 0
        assertNull(plan(sweep = listOf(Point(450.0, 500.0)), generator = {
            generated++
            UUID.randomUUID().toString()
        }))
        assertEquals(0, generated)
    }

    @Test fun geometryUncertaintyBlocksEverythingBeforeAllocatingIds() {
        val ambiguous = row(second, 1, envelope(second, transform = "[1,0,0,1,0,0]",
            points = """[{"x":50,"y":105,"tMs":0},{"x":60,"y":105,"tMs":10}]"""))
        var generated = 0
        try {
            plan(content = note(listOf(row(first, 0), ambiguous)), generator = {
                generated++
                UUID.randomUUID().toString()
            })
            fail("Expected uncertain geometry to stop the entire plan")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, generated)
    }

    @Test fun rejectsDuplicateGeneratedIds() {
        try {
            plan(generator = { other })
            fail("Expected duplicate generated fragment IDs")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsNewIdMatchingAnExistingOtherPageElement() {
        val elsewhere = StoredPage("page-other", 1L, 600.0, 800.0,
            """{"type":"blank","color":"#FFFFFF"}""", 1L,
            listOf(row(other, 0)))
        val content = note().copy(pages = note().pages + elsewhere)
        try {
            plan(content, generator = { other })
            fail("Expected note-wide ID collision")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsInvalidGeneratedUuid() {
        try {
            plan(generator = { "invalid" })
            fail("Expected invalid UUID")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsStaleScanRevision() {
        assertFails { EditorEraserPartialStrokeReplacementPlanner.plan(note(), "page-one",
            listOf(Point(50.0, 100.0)), 4.0, scanNoteRevision = 6L) }
        assertFails { EditorEraserPartialStrokeReplacementPlanner.plan(note(), "page-one",
            listOf(Point(50.0, 100.0)), 4.0, scanPageRevision = 1L) }
    }

    @Test fun rejectsDuplicateElementIdsAcrossPagesAndInvalidOrder() {
        val duplicated = StoredPage("page-duplicate", 1L, 600.0, 800.0,
            """{"type":"blank","color":"#FFFFFF"}""", 1L,
            listOf(row(first, 0)))
        assertFails { plan(note().copy(pages = note().pages + duplicated)) }
        assertFails { plan(note(listOf(row(first, 2)))) }
    }

    @Test fun preservesAffineTransformAndBrushMetadata() {
        val rotated = row(first, 0, envelope(first, transform = "[0,1,-1,0,200,100]"))
        val result = requireNotNull(plan(note(listOf(rotated)), sweep = listOf(Point(100.0, 150.0))))
        assertEquals(2, result.replacements.single().fragmentIds.size)
        result.replacements.single().fragmentEnvelopes.forEach { raw ->
            val json = Json.parseToJsonElement(raw).jsonObject
            assertEquals("[0,1,-1,0,200,100]", json.getValue("transform").toString())
            assertEquals("pencil", json.getValue("payload").jsonObject.getValue("brush").jsonObject.getValue("kind").jsonPrimitive.content)
        }
    }

    @Test fun producesExactForwardAndInverseCommandsWithoutChangingSource() {
        val output = requireNotNull(plan())
        val replacement = output.replacements.single()
        val pair = com.notebook.app.data.content.model.DocumentEdit.PartialStrokeReplacement(
            replacement.original.envelopeJson, replacement.fragmentEnvelopes,
        )
        assertEquals(
            com.notebook.app.data.content.model.DocumentEdit.ApplyPartialStrokeReplacements(
                "page-one", output.originalElementIdsInOrder,
                output.resultingElementIdsInOrder, listOf(pair),
            ), output.forwardEdit,
        )
        assertEquals(
            com.notebook.app.data.content.model.DocumentEdit.RestorePartialStrokeReplacements(
                "page-one", output.originalElementIdsInOrder,
                output.resultingElementIdsInOrder, listOf(pair),
            ), output.inverseEdit,
        )
        assertEquals(replacement.fragmentIds, output.resultingElementIdsInOrder)
    }

    @Test fun doesNotModifySourceWhenPlanning() {
        val content = note()
        val before = content.pages.single().elements.single().envelopeJson
        requireNotNull(plan(content))
        assertEquals(before, content.pages.single().elements.single().envelopeJson)
        assertEquals(7L, content.revision)
    }

    private fun assertFails(action: () -> Any?) {
        try { action(); fail("Expected unsafe planner input to be rejected") }
        catch (_: IllegalArgumentException) { }
    }
}
