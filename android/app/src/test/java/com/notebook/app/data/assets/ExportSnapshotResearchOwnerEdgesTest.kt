package com.notebook.app.data.assets

import com.notebook.app.data.assets.DocumentAssetReferenceInspector.Finding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STO-08 C3-A2-I3-B B1: pure JVM, test-local read-side owner projection model.
 * Does NOT claim a Room transaction, global completeness, or export eligibility.
 */
class ExportSnapshotResearchOwnerEdgesTest {
    private val imageAsset = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val otherAsset = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val pageId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val elementId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    private val secondElementId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"

    private data class PageRow(val id: String, val backgroundJson: String, val edgeAssetId: String?)
    private data class ElementRow(
        val id: String, val kind: String, val json: String, val edgeAssetId: String?,
    )
    private data class Observation(val owners: List<Pair<String, String>>, val distinctAssets: Set<String>)

    /** A strictly scoped negative/positive fixture model, not a production snapshot reader. */
    private fun inspect(pages: List<PageRow>, elements: List<ElementRow>): Observation? {
        val occurrences = mutableListOf<Pair<String, String>>()
        val pageIds = mutableSetOf<String>()
        for (page in pages) {
            if (!pageIds.add(page.id)) return null
            val finding = DocumentAssetReferenceInspector.pageBackground(page.backgroundJson)
            // B1 intentionally excludes PDF entirely.
            if (finding != Finding.NoDirectReference || page.edgeAssetId != null) return null
        }
        val ids = mutableSetOf<String>()
        for (element in elements) {
            if (!ids.add(element.id)) return null
            val finding = DocumentAssetReferenceInspector.element(element.json)
            when (finding) {
                is Finding.Reference -> {
                    if (element.kind != "image" || element.edgeAssetId != finding.assetId) return null
                    // Verify the indexed envelope ID, not merely the embedded asset reference.
                    if (!element.json.contains("\"id\":\"${element.id}\"")) return null
                    occurrences += element.id to finding.assetId
                }
                Finding.NoDirectReference -> {
                    if (element.kind == "image" || element.edgeAssetId != null) return null
                    // Test-local discriminator guards against recognized JSON of a different kind.
                    if (!element.json.contains("\"type\":\"${element.kind}\"")) return null
                }
                is Finding.Unresolved -> return null
            }
        }
        return Observation(occurrences.toList(), occurrences.mapTo(linkedSetOf()) { it.second })
    }

    private fun blank() = """{"type":"blank","color":"#FFFFFF"}"""
    private fun image(id: String, asset: String) = """{"id":"$id","type":"image","locked":false,"transform":[1,0,0,1,0,0],"payload":{"assetId":"$asset","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""
    private fun text(id: String) = """{"id":"$id","type":"text","locked":false,"transform":[1,0,0,1,0,0],"payload":{"text":"hello","frame":{"x":0,"y":0,"width":2,"height":2},"style":{}}}"""

    @Test fun mediaFreeRowsWithNoEdgesAreObservedWithoutCopyKeys() {
        val result = inspect(listOf(PageRow(pageId, blank(), null)), listOf(ElementRow(elementId, "text", text(elementId), null)))
        assertEquals(emptyList<Pair<String, String>>(), result?.owners)
        assertEquals(emptySet<String>(), result?.distinctAssets)
    }

    @Test fun imageEdgeMustExactlyMatchCanonicalAssetId() {
        val row = ElementRow(elementId, "image", image(elementId, imageAsset), imageAsset)
        val result = inspect(listOf(PageRow(pageId, blank(), null)), listOf(row))
        assertEquals(listOf(elementId to imageAsset), result?.owners)
        assertEquals(setOf(imageAsset), result?.distinctAssets)
        assertEquals(null, inspect(listOf(PageRow(pageId, blank(), null)), listOf(row.copy(edgeAssetId = otherAsset))))
        assertEquals(null, inspect(listOf(PageRow(pageId, blank(), null)), listOf(row.copy(edgeAssetId = null))))
    }

    @Test fun extraEdgeOnMediaFreeOwnerIsRejected() {
        assertEquals(null, inspect(listOf(PageRow(pageId, blank(), otherAsset)), emptyList()))
        assertEquals(null, inspect(listOf(PageRow(pageId, blank(), null)), listOf(ElementRow(elementId, "text", text(elementId), otherAsset))))
    }

    @Test fun malformedUnknownHiddenOrPdfContentAlwaysRejects() {
        val base = listOf(PageRow(pageId, blank(), null))
        assertEquals(null, inspect(base, listOf(ElementRow(elementId, "image", "{", imageAsset))))
        assertEquals(null, inspect(base, listOf(ElementRow(elementId, "video", """{"type":"video","assetId":"$imageAsset"}""", null))))
        assertEquals(null, inspect(base, listOf(ElementRow(elementId, "text", text(elementId).replace("\"style\":{}", "\"style\":{\"assetId\":\"$imageAsset\"}"), null))))
        val pdf = """{"type":"pdfPage","assetId":"$imageAsset","pageIndex":0,"displayBox":"crop"}"""
        assertEquals(null, inspect(listOf(PageRow(pageId, pdf, imageAsset)), emptyList()))
    }

    @Test fun separateOwnersOfSameAssetAreNotCollapsedIntoOneEdge() {
        val result = inspect(listOf(PageRow(pageId, blank(), null)), listOf(
            ElementRow(elementId, "image", image(elementId, imageAsset), imageAsset),
            ElementRow(secondElementId, "image", image(secondElementId, imageAsset), imageAsset),
        ))
        assertEquals(2, result?.owners?.size)
        assertEquals(1, result?.distinctAssets?.size)
        assertTrue(result!!.owners.map { it.first }.toSet().containsAll(setOf(elementId, secondElementId)))
    }

    @Test fun duplicateOwnersOrEnvelopeIndexMismatchRejects() {
        val same = ElementRow(elementId, "image", image(elementId, imageAsset), imageAsset)
        assertEquals(null, inspect(listOf(PageRow(pageId, blank(), null)), listOf(same, same)))
        assertEquals(null, inspect(listOf(PageRow(pageId, blank(), null)), listOf(same.copy(id = secondElementId))))
        assertEquals(null, inspect(listOf(PageRow(pageId, blank(), null)), listOf(same.copy(kind = "text"))))
    }

    @Test fun modelCannotCertifyRoomTransactionOrGlobalOwnershipState() {
        val result = inspect(listOf(PageRow(pageId, blank(), null)), emptyList())
        assertTrue(result != null)
        // No trusted/verified capability exists in this model.
        assertTrue(result!!.owners.isEmpty())
    }
}
