package com.notebook.app.ui.editor.selection

import com.notebook.app.data.content.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SelectionFoundationTest {
    private val note = "00000000-0000-4000-8000-000000000001"
    private val page = "00000000-0000-4000-8000-000000000002"
    private val id = "00000000-0000-4000-8000-000000000003"
    private val other = "00000000-0000-4000-8000-000000000004"
    private val frame = """{"x":1.25,"y":2,"width":30,"height":40}"""
    private fun row(kind: String = "text", locked: Boolean = false): StoredElement {
        val payload = if (kind == "text")
            """{"text":"שלום 🌍 café","frame":$frame,"style":{"fontFamily":"serif","fontSize":20,"color":"#aBcDeF","bold":true,"italic":false,"underline":true,"alignment":"center"}}"""
        else """{"assetId":"$other","frame":$frame,"opacity":0.5}"""
        return StoredElement(id, 0, kind,
            " \n" + """{"id":"$id","type":"$kind","locked":$locked,"transform":[1,0.25,0,1,2,3],"payload":$payload}""" + "\n", 7)
    }
    private fun content(rows: List<StoredElement> = listOf(row())) =
        StoredNoteContent(note, 7, listOf(StoredPage(page, 0, 100.0, 200.0, "blank", 2, rows)))
    private fun state(refs: List<PageElementRef> = listOf(PageElementRef(page, id))) =
        PageSelectionState(note, page, 7, refs)
    private fun decode(row: StoredElement) = StoredFrameElementDecoder.decode(page, row)
    private fun change(row: StoredElement, key: String, value: JsonElement): StoredElement {
        val json = Json.parseToJsonElement(row.envelopeJson).jsonObject
        return row.copy(envelopeJson = JsonObject(json + (key to value)).toString())
    }
    private fun payload(row: StoredElement, key: String, value: JsonElement): StoredElement {
        val p = Json.parseToJsonElement(row.envelopeJson).jsonObject.getValue("payload").jsonObject
        return change(row, "payload", JsonObject(p + (key to value)))
    }
    private fun rejects(block: () -> Unit) { assertThrows(RuntimeException::class.java, block) }

    @Test fun orderedUniquePageReferences() {
        val refs = listOf(PageElementRef(page, id), PageElementRef(page, other))
        assertEquals(refs, state(refs).members)
        rejects { state(refs + refs[0]) }
        rejects { state(listOf(PageElementRef(other, id))) }
        rejects { PageSelectionState(note, page, -1, refs) }
    }
    @Test fun referenceListIsDefensivelyFrozen() {
        val refs = mutableListOf(PageElementRef(page, id))
        val selected = state(refs)
        refs.clear()
        assertEquals(1, selected.members.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (selected.members as MutableList).clear()
        }
    }
    @Test fun snapshotRetainsExactRowsAndLock() {
        val source = row(locked = true)
        val snapshot = SelectionSnapshot.capture(content(listOf(source)), state())
        assertEquals(source, snapshot.members.single().source)
        assertTrue(snapshot.members.single().locked)
        assertEquals(source.envelopeJson, snapshot.page.elements.single().envelopeJson)
        assertEquals(SelectionValidation.Current, snapshot.validate(content(listOf(source))))
    }
    @Test fun snapshotDefensivelyFreezesPageAndMemberLists() {
        val rows = mutableListOf(row())
        val pages = content(rows).pages.toMutableList()
        val snapshot = SelectionSnapshot.capture(StoredNoteContent(note, 7, pages), state())
        rows.clear(); pages.clear()
        assertEquals(1, snapshot.page.elements.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.page.elements as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.members as MutableList).clear()
        }
    }
    @Test fun staleRevisionAndWrongNoteAreExplicit() {
        val snapshot = SelectionSnapshot.capture(content(), state())
        assertEquals(SelectionValidation.StaleRevision, snapshot.validate(content().copy(revision = 8)))
        assertTrue(snapshot.validate(content().copy(noteId = other)) is SelectionValidation.Invalid)
        rejects { SelectionSnapshot.capture(content().copy(revision = 8), state()) }
    }
    @Test fun changedOrMissingMembersRejectWithoutRetargeting() {
        val snapshot = SelectionSnapshot.capture(content(), state())
        assertTrue(snapshot.validate(content(emptyList())) is SelectionValidation.Invalid)
        assertTrue(snapshot.validate(content(listOf(row().copy(revision = 8)))) is SelectionValidation.Invalid)
        assertTrue(snapshot.validate(content(listOf(row().copy(envelopeJson = row().envelopeJson + " "))))
            is SelectionValidation.Invalid)
    }
    @Test fun pageIdentityAndDimensionsMustMatch() {
        val c = content()
        val snapshot = SelectionSnapshot.capture(c, state())
        for (p in listOf(c.pages.single().copy(id = other), c.pages.single().copy(width = 101.0),
            c.pages.single().copy(height = 201.0))) {
            assertTrue(snapshot.validate(c.copy(pages = listOf(p))) is SelectionValidation.Invalid)
        }
        rejects { SelectionSnapshot.capture(c.copy(pages = listOf(c.pages.single().copy(width = Double.NaN))), state()) }
    }
    @Test fun canonicalOrderAndMissingReferencesReject() {
        val first = row()
        val second = change(row(), "id", JsonPrimitive(other)).copy(id = other, orderIndex = 1)
        val c = content(listOf(first, second))
        rejects { SelectionSnapshot.capture(c, state(listOf(PageElementRef(page, other), PageElementRef(page, id)))) }
        val snapshot = SelectionSnapshot.capture(c, state())
        assertTrue(snapshot.validate(content(listOf(second.copy(orderIndex = 0), first.copy(orderIndex = 1))))
            is SelectionValidation.Invalid)
        rejects { SelectionSnapshot.capture(content(), state(listOf(PageElementRef(page, other)))) }
    }
    @Test fun duplicateNoteWideIdsReject() {
        val c = content()
        rejects { SelectionSnapshot.capture(c.copy(pages = c.pages + c.pages.single().copy(id = other)), state()) }
    }
    @Test fun unicodeAndWholeBoxStylesAreDecodedWithoutNormalization() {
        val source = row()
        val text = decode(source) as StoredFrameElement.Text
        assertEquals("שלום 🌍 café", text.text)
        assertEquals("serif", text.style.fontFamily)
        assertTrue(text.style.bold && text.style.underline)
        assertFalse(text.style.italic)
        assertEquals("#aBcDeF", text.style.color)
        assertEquals("center", text.style.alignment)
        assertEquals(20.0, text.style.fontSize, 0.0)
        assertEquals(source.envelopeJson, text.source.envelopeJson)
        assertEquals(Json.parseToJsonElement(source.envelopeJson).jsonObject["payload"], text.payload)
    }
    @Test fun imageStructureDoesNotVerifyAvailability() {
        val source = row("image")
        val image = decode(source) as StoredFrameElement.Image
        assertEquals(other, image.assetId)
        assertNull(image.crop)
        assertEquals(0.5, image.opacity, 0.0)
        assertEquals(ImageAvailability.UNVERIFIED, image.availability)
        assertEquals(source.envelopeJson, image.source.envelopeJson)
    }
    @Test fun optionalCropRetained() {
        val crop = Json.parseToJsonElement("""{"x":0.25,"y":0,"width":0.75,"height":1}""")
        val image = decode(payload(row("image"), "crop", crop)) as StoredFrameElement.Image
        assertEquals(CanonicalFrame(0.25, 0.0, 0.75, 1.0), image.crop)
        assertEquals(crop, image.payload["crop"])
    }
    @Test fun cropBoundsReject() {
        for (crop in listOf(
            """{"x":0.5,"y":0,"width":0.6,"height":1}""",
            """{"x":0,"y":0.5,"width":1,"height":0.6}""",
            """{"x":-0.1,"y":0,"width":1,"height":1}""",
            """{"x":0,"y":0,"width":0,"height":1}""", "null")) {
            rejects { decode(payload(row("image"), "crop", Json.parseToJsonElement(crop))) }
        }
    }
    @Test fun opacityAndFrameValidation() {
        for (v in listOf(JsonPrimitive(-0.1), JsonPrimitive(1.1), JsonPrimitive("0.5"), JsonNull))
            rejects { decode(payload(row("image"), "opacity", v)) }
        rejects { decode(payload(row(), "frame", Json.parseToJsonElement(frame.replace("30", "-1")))) }
    }
    @Test fun wrongIdentityKindLockAndAffineReject() {
        rejects { decode(row().copy(id = other)) }
        rejects { decode(row().copy(kind = "image")) }
        rejects { decode(change(row(), "locked", JsonPrimitive("false"))) }
        for (t in listOf("[1,0,0,0,0,0]", "[1,0]", "[1e999,0,0,1,0,0]", "[1,0,0,1,null,0]"))
            rejects { decode(change(row(), "transform", Json.parseToJsonElement(t))) }
    }
    @Test fun lockedFramesRemainReadableWithoutEditPermission() {
        assertTrue(decode(row(locked = true)).locked)
        val image = decode(row("image", true)) as StoredFrameElement.Image
        assertTrue(image.locked)
        assertEquals(ImageAvailability.UNVERIFIED, image.availability)
    }
    @Test fun unsupportedAndMalformedFieldsReject() {
        rejects { decode(row().copy(envelopeJson = "{}")) }
        rejects { decode(change(row(), "extra", JsonPrimitive(1))) }
        rejects { decode(payload(row(), "extra", JsonPrimitive(1))) }
        rejects { decode(payload(row("image"), "assetId", JsonPrimitive("path/to/image"))) }
        val p = Json.parseToJsonElement(row().envelopeJson).jsonObject["payload"]!!.jsonObject
        val style = p["style"]!!.jsonObject
        for ((key, value) in listOf("fontFamily" to JsonPrimitive("native-font"),
            "fontSize" to JsonPrimitive(0), "alignment" to JsonPrimitive("justify"),
            "bold" to JsonPrimitive(1), "color" to JsonPrimitive("#123"))) {
            rejects { decode(payload(row(), "style", JsonObject(style + (key to value)))) }
        }
    }
}
