package com.notebook.app.ui.editor.viewport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportInteractionTest {
    private val epsilon = 1e-8
    private val mixed = requireNotNull(ViewportGeometry.layoutOrNull(listOf(
        PageSizeDu("portrait", 612.0, 792.0),
        PageSizeDu("landscape", 792.0, 612.0),
    )))

    @Test fun tappedSecondPageReturnsCorrectIdAndLocalCoordinatesWithPanAndZoom() {
        val transform = ViewportTransform(2.0, 30.0, -100.0)
        val viewport = PxSize(2000.0, 2000.0)
        val hit = requireNotNull(ViewportInteraction.hitTestVisiblePage(
            PxPoint(50.0, 1572.0), mixed, transform, viewport,
        ))
        assertEquals("landscape", hit.pageId)
        assertEquals(10.0, hit.localPointDu.x, epsilon)
        assertEquals(20.0, hit.localPointDu.y, epsilon)
    }

    @Test fun gapsShortPageMarginsAndViewportOutsideHaveNoTarget() {
        val identity = ViewportTransform(1.0, 0.0, 0.0)
        val viewport = PxSize(1000.0, 1000.0)
        assertNull(ViewportInteraction.hitTestVisiblePage(PxPoint(100.0, 800.0), mixed, identity, viewport))
        assertNull(ViewportInteraction.hitTestVisiblePage(PxPoint(20.0, 100.0), mixed, identity, viewport))
        assertNull(ViewportInteraction.hitTestVisiblePage(PxPoint(-1.0, 850.0), mixed, identity, viewport))
        assertNull(ViewportInteraction.hitTestVisiblePage(PxPoint(1000.0, 850.0), mixed, identity, viewport))
        assertNull(ViewportInteraction.hitTestVisiblePage(PxPoint(100.0, Double.NaN), mixed, identity, viewport))
        assertNull(ViewportInteraction.hitTestVisiblePage(PxPoint(100.0, 200.0), mixed, identity,
            PxSize(0.0, 1000.0)))
    }

    private val longPage = requireNotNull(ViewportGeometry.layoutOrNull(
        listOf(PageSizeDu("page-1", 600.0, 1800.0)),
    ))

    @Test fun resizingPreservesRelativeZoomAndCenterPointWithoutEdgeClamping() {
        val oldSize = PxSize(600.0, 700.0)
        val newSize = PxSize(420.0, 950.0)
        val oldFit = requireNotNull(ViewportGeometry.fitToWidthOrNull(longPage, oldSize, 16.0))
        val oldTransform = requireNotNull(ViewportGeometry.clampOrNull(
            longPage, oldSize, 16.0,
            ViewportTransform(oldFit.scalePxPerDu * 2.0, -260.0, -370.0),
        ))
        val centerBefore = requireNotNull(ViewportGeometry.viewportToWorldOrNull(
            PxPoint(oldSize.width / 2.0, oldSize.height / 2.0), oldTransform,
        ))
        val newTransform = requireNotNull(ViewportInteraction.restoreAfterChangeOrNull(
            longPage, oldSize, 16.0, oldTransform, longPage, newSize, 16.0,
        ))
        val newFit = requireNotNull(ViewportGeometry.fitToWidthOrNull(longPage, newSize, 16.0))
        val centerAfter = requireNotNull(ViewportGeometry.viewportToWorldOrNull(
            PxPoint(newSize.width / 2.0, newSize.height / 2.0), newTransform,
        ))
        assertEquals(2.0, newTransform.scalePxPerDu / newFit.scalePxPerDu, epsilon)
        assertEquals(centerBefore.x, centerAfter.x, epsilon)
        assertEquals(centerBefore.y, centerAfter.y, epsilon)
    }

    @Test fun pageReorderRestoresAnchorByStableIdNotOldPosition() {
        val previous = requireNotNull(ViewportGeometry.layoutOrNull(listOf(
            PageSizeDu("first", 500.0, 1500.0), PageSizeDu("second", 500.0, 1500.0),
        )))
        val reordered = requireNotNull(ViewportGeometry.layoutOrNull(listOf(
            PageSizeDu("second", 500.0, 1500.0), PageSizeDu("first", 500.0, 1500.0),
        )))
        val viewport = PxSize(550.0, 600.0)
        val current = ViewportTransform(1.0, 25.0, -1600.0) // center at second page's local y=376
        val restored = requireNotNull(ViewportInteraction.restoreAfterChangeOrNull(
            previous, viewport, 16.0, current, reordered, viewport, 16.0,
        ))
        val hit = requireNotNull(ViewportInteraction.hitTestVisiblePage(
            PxPoint(275.0, 300.0), reordered, restored, viewport,
        ))
        assertEquals("second", hit.pageId)
        assertEquals(376.0, hit.localPointDu.y, epsilon)
    }

    @Test fun removingFocusedPageFallsBackToNearestSurvivingIndex() {
        val previous = requireNotNull(ViewportGeometry.layoutOrNull(listOf(
            PageSizeDu("a", 500.0, 1500.0), PageSizeDu("b", 500.0, 1500.0),
            PageSizeDu("c", 500.0, 1500.0),
        )))
        val remaining = requireNotNull(ViewportGeometry.layoutOrNull(listOf(
            PageSizeDu("a", 500.0, 1500.0), PageSizeDu("c", 500.0, 1500.0),
        )))
        val viewport = PxSize(550.0, 600.0)
        val current = ViewportTransform(1.0, 25.0, -1600.0)
        val restored = requireNotNull(ViewportInteraction.restoreAfterChangeOrNull(
            previous, viewport, 16.0, current, remaining, viewport, 16.0,
        ))
        assertEquals("c", ViewportInteraction.hitTestVisiblePage(
            PxPoint(275.0, 300.0), remaining, restored, viewport,
        )?.pageId)
    }

    @Test fun switchingToUnrelatedPageIdsStartsAtFit() {
        val replacement = requireNotNull(ViewportGeometry.layoutOrNull(listOf(
            PageSizeDu("unrelated", 600.0, 500.0),
        )))
        val oldSize = PxSize(600.0, 700.0)
        val newSize = PxSize(450.0, 600.0)
        val oldTransform = ViewportTransform(2.0, -300.0, -400.0)
        val changed = ViewportInteraction.restoreAfterChangeOrNull(
            longPage, oldSize, 16.0, oldTransform, replacement, newSize, 16.0,
        )
        assertEquals(ViewportGeometry.fitToWidthOrNull(replacement, newSize, 16.0), changed)
    }

    @Test fun invalidTargetDimensionsAndInvalidNewViewportFailSafely() {
        val original = requireNotNull(ViewportGeometry.fitToWidthOrNull(
            longPage, PxSize(600.0, 700.0), 16.0,
        ))
        assertNull(ViewportInteraction.restoreAfterChangeOrNull(
            longPage, PxSize(600.0, 700.0), 16.0, original,
            longPage, PxSize(20.0, 700.0), 16.0,
        ))
        assertTrue(ViewportInteraction.restoreAfterChangeOrNull(
            longPage, PxSize(600.0, 700.0), 16.0,
            ViewportTransform(Double.NaN, 0.0, 0.0),
            longPage, PxSize(500.0, 700.0), 16.0,
        ) != null)
    }

    @Test fun savedAnchorSurvivesActivityRecreationAndKeepsZoomAndPagePosition() {
        val landscape = PxSize(1000.0, 650.0)
        val portrait = PxSize(650.0, 1000.0)
        val gutter = 16.0
        val oldFit = requireNotNull(ViewportGeometry.fitToWidthOrNull(longPage, landscape, gutter))
        val oldTransform = requireNotNull(ViewportGeometry.clampOrNull(
            longPage, landscape, gutter,
            ViewportTransform(oldFit.scalePxPerDu * 2.2, -550.0, -1240.0),
        ))
        // Capture primitives before Activity destruction; new runtime has no old session object.
        val bookmark = requireNotNull(ViewportInteraction.captureAnchorOrNull(
            longPage, landscape, gutter, oldTransform,
        ))
        val restored = requireNotNull(ViewportInteraction.restoreAnchorOrNull(
            bookmark, longPage, portrait, gutter,
        ))
        val newFit = requireNotNull(ViewportGeometry.fitToWidthOrNull(longPage, portrait, gutter))
        val restoredBookmark = requireNotNull(ViewportInteraction.captureAnchorOrNull(
            longPage, portrait, gutter, restored,
        ))
        assertEquals("page-1", restoredBookmark.pageId)
        assertEquals(2.2, restored.scalePxPerDu / newFit.scalePxPerDu, epsilon)
        assertEquals(bookmark.localXDu, restoredBookmark.localXDu, epsilon)
        assertEquals(bookmark.localYDu, restoredBookmark.localYDu, epsilon)
    }

    @Test fun savedAnchorRestoresStablePageIdAfterReorder() {
        val old = requireNotNull(ViewportGeometry.layoutOrNull(listOf(
            PageSizeDu("a", 500.0, 1600.0), PageSizeDu("b", 500.0, 1600.0),
        )))
        val new = requireNotNull(ViewportGeometry.layoutOrNull(listOf(
            PageSizeDu("b", 500.0, 1600.0), PageSizeDu("a", 500.0, 1600.0),
        )))
        val viewport = PxSize(600.0, 600.0)
        val fit = requireNotNull(ViewportGeometry.fitToWidthOrNull(old, viewport, 16.0))
        val oldTransform = requireNotNull(ViewportGeometry.clampOrNull(
            old, viewport, 16.0,
            ViewportTransform(fit.scalePxPerDu * 2.0, -200.0, -3600.0),
        ))
        val bookmark = requireNotNull(ViewportInteraction.captureAnchorOrNull(
            old, viewport, 16.0, oldTransform,
        ))
        assertEquals("b", bookmark.pageId)
        val restored = requireNotNull(ViewportInteraction.restoreAnchorOrNull(
            bookmark, new, viewport, 16.0,
        ))
        assertEquals("b", requireNotNull(ViewportInteraction.captureAnchorOrNull(
            new, viewport, 16.0, restored,
        )).pageId)
    }

    @Test fun savedAnchorFallsBackSafelyWhenPageMissingOrValuesInvalid() {
        val viewport = PxSize(600.0, 700.0)
        val fit = requireNotNull(ViewportGeometry.fitToWidthOrNull(longPage, viewport, 16.0))
        assertEquals(fit, ViewportInteraction.restoreAnchorOrNull(
            ViewportAnchor("removed", 10.0, 20.0, 2.0), longPage, viewport, 16.0,
        ))
        assertEquals(fit, ViewportInteraction.restoreAnchorOrNull(
            ViewportAnchor("page-1", 10.0, 20.0, Double.NaN), longPage, viewport, 16.0,
        ))
    }
}
