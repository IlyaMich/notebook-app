package com.notebook.app.data.assets

import com.notebook.app.data.assets.AssetProjectionDiagnosticComparator as Comparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetProjectionDiagnosticComparatorTest {
    private fun page(id: String = "page-1", asset: String = "asset-1", trash: Boolean = false) =
        AssetOwnerInventory.Owner(asset, "note-1", id, null,
            AssetOwnerInventory.Kind.PDF_BACKGROUND, trash)

    private fun image(id: String = "element-1", asset: String = "asset-2", trash: Boolean = false) =
        AssetOwnerInventory.Owner(asset, "note-1", "page-1", id,
            AssetOwnerInventory.Kind.IMAGE_ELEMENT, trash)

    private fun source(vararg owners: AssetOwnerInventory.Owner,
                       issues: List<AssetOwnerInventory.Issue> = emptyList()) =
        AssetOwnerInventory.Report(owners.toList(), issues, emptySet())

    private fun diagnose(
        canonical: AssetOwnerInventory.Report,
        pages: List<Comparator.PageEdge> = emptyList(),
        elements: List<Comparator.ElementEdge> = emptyList(),
    ) = Comparator.compareObserved(canonical, pages, elements)

    private fun contains(result: Comparator.Diagnostic, kind: Comparator.Kind) =
        result.findings.any { it.kind == kind }

    @Test fun matchingPageAndImageObservationsHaveNoObservedMismatch() {
        val result = diagnose(source(page(), image()),
            listOf(Comparator.PageEdge("page-1", "asset-1")),
            listOf(Comparator.ElementEdge("element-1", "asset-2")))
        assertFalse(result.hasObservedMismatch)
    }

    @Test fun missingPageProjectionIsReported() {
        assertTrue(contains(diagnose(source(page())), Comparator.Kind.MISSING_PROJECTION_EDGE))
    }

    @Test fun missingImageProjectionIsReported() {
        assertTrue(contains(diagnose(source(image())), Comparator.Kind.MISSING_PROJECTION_EDGE))
    }

    @Test fun mismatchedPageAssetIsReported() {
        assertTrue(contains(diagnose(source(page()),
            listOf(Comparator.PageEdge("page-1", "wrong"))), Comparator.Kind.MISMATCHED_ASSET_ID))
    }

    @Test fun mismatchedImageAssetIsReported() {
        assertTrue(contains(diagnose(source(image()), elements =
            listOf(Comparator.ElementEdge("element-1", "wrong"))), Comparator.Kind.MISMATCHED_ASSET_ID))
    }

    @Test fun unexpectedPageProjectionIsReported() {
        assertTrue(contains(diagnose(source(),
            listOf(Comparator.PageEdge("orphan", "asset"))), Comparator.Kind.UNEXPECTED_PROJECTION_EDGE))
    }

    @Test fun unexpectedElementProjectionIsReported() {
        assertTrue(contains(diagnose(source(), elements =
            listOf(Comparator.ElementEdge("orphan", "asset"))), Comparator.Kind.UNEXPECTED_PROJECTION_EDGE))
    }

    @Test fun duplicatePageProjectionIsRejectedEvenWhenMatching() {
        assertTrue(contains(diagnose(source(page()), listOf(
            Comparator.PageEdge("page-1", "asset-1"), Comparator.PageEdge("page-1", "asset-1"))),
            Comparator.Kind.DUPLICATE_PROJECTION_EDGE))
    }

    @Test fun duplicateCanonicalImageOwnerIsReported() {
        assertTrue(contains(diagnose(source(image(), image()), elements =
            listOf(Comparator.ElementEdge("element-1", "asset-2"))),
            Comparator.Kind.DUPLICATE_CANONICAL_OWNER))
    }

    @Test fun unresolvedCanonicalIssueFailsClosedWithMatchingRows() {
        val issue = AssetOwnerInventory.Issue("note-1", "page-1", null, "unknown JSON")
        val result = diagnose(source(page(), issues = listOf(issue)),
            listOf(Comparator.PageEdge("page-1", "asset-1")))
        assertTrue(contains(result, Comparator.Kind.UNRESOLVED_CANONICAL_CONTENT))
    }

    @Test fun trashOwnerIsNeverExcludedFromComparison() {
        val result = diagnose(source(page(trash = true)))
        assertTrue(contains(result, Comparator.Kind.MISSING_PROJECTION_EDGE))
    }

    @Test fun malformedCanonicalOwnerCannotMatch() {
        val result = diagnose(source(image(id = "")))
        assertTrue(contains(result, Comparator.Kind.INVALID_CANONICAL_OWNER))
    }

    @Test fun outputOrderIsStableRegardlessOfProjectionOrder() {
        val edges = listOf(Comparator.PageEdge("b", "x"), Comparator.PageEdge("a", "y"))
        assertEquals(diagnose(source(), edges).findings,
            diagnose(source(), edges.reversed()).findings)
    }
}