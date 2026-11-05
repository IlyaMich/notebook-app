package com.notebook.app.data.assets

import com.notebook.app.data.assets.DocumentAssetReferenceInspector.Finding
import com.notebook.app.data.assets.DocumentAssetReferenceInspector.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentAssetReferenceInspectorTest {
    private val pageId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val assetId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val otherAssetId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    private fun element(type: String, payload: String): String = """
        {
          "id":"$pageId", "type":"$type", "locked":false,
          "transform":[1,0,0,1,0,0], "payload":$payload
        }
    """.trimIndent()

    @Test fun pdfPageReportsItsDirectOwner() {
        val raw = """{"type":"pdfPage","assetId":"$assetId","pageIndex":0,"displayBox":"crop"}"""
        assertEquals(Finding.Reference(assetId), DocumentAssetReferenceInspector.pageBackground(raw))
    }

    @Test fun imageReportsItsDirectOwner() {
        val raw = element("image", """{"assetId":"$otherAssetId","frame":{"x":0,"y":1,"width":8,"height":9},"opacity":1}""")
        assertEquals(Finding.Reference(otherAssetId), DocumentAssetReferenceInspector.element(raw))
    }

    @Test fun recognizedNonMediaBackgroundsReportNoDirectOwner() {
        val background = """{"type":"blank","color":"#FFFFFF"}"""
        val ruled = """{"type":"ruled","color":"#FFFFFF","patternColor":"#000000","spacing":20}"""
        assertEquals(Finding.NoDirectReference, DocumentAssetReferenceInspector.pageBackground(background))
        assertEquals(Finding.NoDirectReference, DocumentAssetReferenceInspector.pageBackground(ruled))
    }

    @Test fun recognizedNonMediaElementReportsNoDirectOwner() {
        val raw = element("text", """{"text":"hello","frame":{"x":0,"y":0,"width":2,"height":2},"style":{}}""")
        // Recognized shape, not proof of full canonical semantic validity.
        assertEquals(Finding.NoDirectReference, DocumentAssetReferenceInspector.element(raw))
    }

    @Test fun structurallyInvalidNonMediaContentIsUnresolved() {
        val raw = element("text", """{"text":"hello","frame":{},"style":{}}""")
        assertEquals(Finding.Unresolved(Reason.INVALID_STRUCTURE),
            DocumentAssetReferenceInspector.element(raw))
    }

    @Test fun malformedJsonIsUnresolved() {
        assertEquals(Finding.Unresolved(Reason.MALFORMED_OR_OVERSIZED_JSON),
            DocumentAssetReferenceInspector.pageBackground("{"))
        assertEquals(Finding.Unresolved(Reason.MALFORMED_OR_OVERSIZED_JSON),
            DocumentAssetReferenceInspector.element("[]"))
    }

    @Test fun unknownTypesAreUnresolved() {
        assertEquals(Finding.Unresolved(Reason.UNKNOWN_TYPE),
            DocumentAssetReferenceInspector.pageBackground("""{"type":"video","assetId":"$assetId"}"""))
        assertEquals(Finding.Unresolved(Reason.UNKNOWN_TYPE),
            DocumentAssetReferenceInspector.element(element("attachment", """{"assetId":"$assetId"}""")))
    }

    @Test fun invalidOrMissingAssetIdsAreUnresolved() {
        val pdf = """{"type":"pdfPage","assetId":"not-an-id","pageIndex":0,"displayBox":"crop"}"""
        assertEquals(Finding.Unresolved(Reason.INVALID_ASSET_ID),
            DocumentAssetReferenceInspector.pageBackground(pdf))
        val image = element("image", """{"frame":{"x":0,"y":0,"width":1,"height":1},"opacity":1}""")
        assertEquals(Finding.Unresolved(Reason.INVALID_STRUCTURE),
            DocumentAssetReferenceInspector.element(image))
    }

    @Test fun unexpectedBackgroundFieldsAndInvalidPageIndexAreUnresolved() {
        assertEquals(Finding.Unresolved(Reason.INVALID_STRUCTURE),
            DocumentAssetReferenceInspector.pageBackground("""{"type":"blank","color":"#FFFFFF","assetId":"$assetId"}"""))
        assertEquals(Finding.Unresolved(Reason.INVALID_STRUCTURE),
            DocumentAssetReferenceInspector.pageBackground("""{"type":"pdfPage","assetId":"$assetId","pageIndex":-1,"displayBox":"crop"}"""))
    }

    @Test fun hiddenAssetFieldInNominallyNonMediaElementIsUnresolved() {
        val raw = element("stroke", """{"encoding":"sampledPointsV1","brush":{"assetId":"$assetId"},"points":[]}""")
        assertEquals(Finding.Unresolved(Reason.POSSIBLE_HIDDEN_REFERENCE),
            DocumentAssetReferenceInspector.element(raw))
    }

    @Test fun invalidImageFrameOrOutOfBoundsCropIsUnresolved() {
        val raw = element("image", """{"assetId":"$assetId","frame":{"x":0,"y":0,"width":0,"height":1},"opacity":1}""")
        assertEquals(Finding.Unresolved(Reason.INVALID_STRUCTURE),
            DocumentAssetReferenceInspector.element(raw))
        val crop = element("image", """{"assetId":"$assetId","frame":{"x":0,"y":0,"width":1,"height":1},"opacity":1,"crop":{"x":0.9,"y":0,"width":0.2,"height":1}}""")
        assertEquals(Finding.Unresolved(Reason.INVALID_STRUCTURE),
            DocumentAssetReferenceInspector.element(crop))
    }

    @Test fun malformedElementEnvelopeIsUnresolved() {
        val raw = element("image", """{"assetId":"$assetId","frame":{"x":0,"y":0,"width":1,"height":1},"opacity":1}""")
            .replace("\"locked\":false", "\"locked\":\"false\"")
        assertEquals(Finding.Unresolved(Reason.INVALID_STRUCTURE),
            DocumentAssetReferenceInspector.element(raw))
    }

    @Test fun oversizeJsonFailsClosed() {
        assertEquals(Finding.Unresolved(Reason.MALFORMED_OR_OVERSIZED_JSON),
            DocumentAssetReferenceInspector.pageBackground(" ".repeat(1_000_001)))
    }
}
