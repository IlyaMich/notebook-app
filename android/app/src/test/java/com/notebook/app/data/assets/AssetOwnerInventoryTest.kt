package com.notebook.app.data.assets

import com.notebook.app.data.assets.AssetOwnerInventory.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetOwnerInventoryTest {
    private val assetId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val pageA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val pageB = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val elementA = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    private val elementB = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"

    private fun pdf() = """{"type":"pdfPage","assetId":"$assetId","pageIndex":0,"displayBox":"crop"}"""
    private fun blank() = """{"type":"blank","color":"#FFFFFF"}"""
    private fun image(id: String) = """{"id":"$id","type":"image","locked":false,"transform":[1,0,0,1,0,0],"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":1,"height":1},"opacity":1}}"""

    @Test fun sharedAssetHasSeparateActiveAndTrashOwners() {
        val collector = AssetOwnerInventory.Collector()
        collector.addBackground("active", false, pageA, "active", pdf())
        collector.addBackground("trashed", true, pageB, "trashed", pdf())
        val report = collector.finish(setOf(assetId))
        assertEquals(2, report.owners.size)
        assertEquals(Kind.PDF_BACKGROUND, report.owners.first().kind)
        assertEquals(setOf(false, true), report.owners.map { it.inTrash }.toSet())
        assertFalse(report.hasUnresolved)
        assertTrue(report.metadataWithoutRecognizedOwner.isEmpty())
    }

    @Test fun duplicateImageOwnersRemainDistinctAndShareBytes() {
        val collector = AssetOwnerInventory.Collector()
        collector.addElement("noteA", false, pageA, elementA, pageA, "image", image(elementA))
        collector.addElement("noteB", false, pageB, elementB, pageB, "image", image(elementB))
        val report = collector.finish(setOf(assetId))
        assertEquals(2, report.owners.size)
        assertEquals(setOf(elementA, elementB), report.owners.mapNotNull { it.elementId }.toSet())
        assertTrue(report.owners.all { it.kind == Kind.IMAGE_ELEMENT })
    }

    @Test fun malformedContentRemainsUnresolvedNotOwnerFree() {
        val collector = AssetOwnerInventory.Collector()
        collector.addBackground("note", true, pageA, "note", "{")
        val report = collector.finish(setOf(assetId))
        assertTrue(report.hasUnresolved)
        assertEquals(0, report.owners.size)
        assertEquals(setOf(assetId), report.metadataWithoutRecognizedOwner)
    }

    @Test fun missingMetadataProducesUnresolvedIssue() {
        val collector = AssetOwnerInventory.Collector()
        collector.addBackground("note", false, pageA, "note", pdf())
        val report = collector.finish(emptySet())
        assertEquals(1, report.owners.size)
        assertTrue(report.hasUnresolved)
        assertTrue(report.issues.any { it.reason == "referenced asset absent from metadata" })
    }

    @Test fun envelopeAndRelationshipMismatchesAreUnresolved() {
        val collector = AssetOwnerInventory.Collector()
        collector.addBackground("note", false, pageA, "wrongNote", blank())
        collector.addElement("note", false, pageA, elementA, pageB, "stroke", image(elementB))
        val report = collector.finish(setOf(assetId))
        assertEquals(1, report.owners.size)
        assertTrue(report.hasUnresolved)
        assertTrue(report.issues.any { it.reason == "page ownership mismatch" })
        assertTrue(report.issues.any { it.reason == "element ownership mismatch" })
        assertTrue(report.issues.any { it.reason == "element envelope/index mismatch" })
    }

    @Test fun oversizedElementRemainsUnresolved() {
        val collector = AssetOwnerInventory.Collector()
        collector.addElement(
            "note", false, pageA, elementA, pageA,
            "image", " ".repeat(1_000_001)
        )
        val report = collector.finish(emptySet())
        assertTrue(report.hasUnresolved)
        assertTrue(report.owners.isEmpty())
        assertTrue(report.issues.any {
            it.elementId == elementA &&
                it.reason == "MALFORMED_OR_OVERSIZED_JSON"
        })
    }
    @Test fun emptyAndNonMediaRowsAreOnlyDiagnosticObservations() {
        val collector = AssetOwnerInventory.Collector()
        collector.addBackground("note", false, pageA, "note", blank())
        val report = collector.finish(emptySet())
        assertTrue(report.owners.isEmpty())
        assertFalse(report.hasUnresolved)
        // A report without recognized owners still does not establish deletion safety.
    }
}
