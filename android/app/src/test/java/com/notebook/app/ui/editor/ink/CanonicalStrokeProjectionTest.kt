
package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.viewport.PositionedPage
import com.notebook.app.ui.editor.viewport.PxPoint
import com.notebook.app.ui.editor.viewport.ViewportTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalStrokeProjectionTest {

    private fun affine(
        vararg values: Double
    ): CanonicalElementAffine =
        requireNotNull(
            CanonicalElementAffine.fromCanonicalOrNull(
                values.toList()
            )
        )

    @Test
    fun identityPreservesCanonicalCoordinates() {
        val transform = affine(
            1.0, 0.0, 0.0, 1.0, 0.0, 0.0
        )

        val original = DuPoint(42.5, 71.25)

        assertEquals(
            original,
            CanonicalStrokeProjection.pagePointOrNull(
                original,
                transform
            )
        )
    }

    @Test
    fun translationAppliesInDocumentUnits() {
        val transform = affine(
            1.0, 0.0, 0.0, 1.0, 12.0, -7.0
        )

        assertEquals(
            DuPoint(32.0, 23.0),
            CanonicalStrokeProjection.pagePointOrNull(
                DuPoint(20.0, 30.0),
                transform
            )
        )
    }

    @Test
    fun rotationUsesCanonicalColumnVectorConvention() {
        // 90 degree rotation around the origin.
        val transform = affine(
            0.0, 1.0, -1.0, 0.0, 0.0, 0.0
        )

        assertEquals(
            DuPoint(-4.0, 3.0),
            CanonicalStrokeProjection.pagePointOrNull(
                DuPoint(3.0, 4.0),
                transform
            )
        )
    }

    @Test
    fun reflectionPreservesExpectedPagePosition() {
        // Reflection around x = 60.
        // Same transform shape used by the shared fixture.
        val transform = affine(
            -1.0, 0.0, 0.0, 1.0, 120.0, 0.0
        )

        assertEquals(
            DuPoint(70.0, 60.0),
            CanonicalStrokeProjection.pagePointOrNull(
                DuPoint(50.0, 60.0),
                transform
            )
        )
    }

    @Test
    fun nonUniformScalingPreservesBothAxes() {
        val transform = affine(
            2.0, 0.0, 0.0, 0.5, -3.0, 10.0
        )

        assertEquals(
            DuPoint(5.0, 14.0),
            CanonicalStrokeProjection.pagePointOrNull(
                DuPoint(4.0, 8.0),
                transform
            )
        )
    }

    @Test
    fun viewportProjectionComposesPageOriginAndZoom() {
        val transform = affine(
            1.0, 0.0, 0.0, 1.0, 3.0, -5.0
        )

        val page = PositionedPage(
            id = "page-2",
            widthDu = 612.0,
            heightDu = 792.0,
            originXDu = 25.0,
            originYDu = 100.0
        )

        val original = DuPoint(10.0, 20.0)

        val firstViewport = ViewportTransform(
            scalePxPerDu = 2.0,
            translationXPx = 10.0,
            translationYPx = 20.0
        )

        val secondViewport = ViewportTransform(
            scalePxPerDu = 4.0,
            translationXPx = -30.0,
            translationYPx = 60.0
        )

        // The canonical page-local position does not change
        // when zoom and pan change.
        val expectedPagePoint = DuPoint(13.0, 15.0)

        assertEquals(
            expectedPagePoint,
            CanonicalStrokeProjection.pagePointOrNull(
                original,
                transform
            )
        )

        assertEquals(
            PxPoint(86.0, 250.0),
            CanonicalStrokeProjection.viewportPointOrNull(
                original,
                transform,
                page,
                firstViewport
            )
        )

        assertEquals(
            PxPoint(122.0, 520.0),
            CanonicalStrokeProjection.viewportPointOrNull(
                original,
                transform,
                page,
                secondViewport
            )
        )

        assertEquals(DuPoint(10.0, 20.0), original)

        assertEquals(
            expectedPagePoint,
            CanonicalStrokeProjection.pagePointOrNull(
                original,
                transform
            )
        )
    }

    @Test
    fun invalidCanonicalTransformsAreRejected() {
        assertNull(
            CanonicalElementAffine.fromCanonicalOrNull(
                listOf(1.0, 0.0, 0.0)
            )
        )

        assertNull(
            CanonicalElementAffine.fromCanonicalOrNull(
                listOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            )
        )

        assertNull(
            CanonicalElementAffine.fromCanonicalOrNull(
                listOf(
                    Double.NaN, 0.0, 0.0,
                    1.0, 0.0, 0.0
                )
            )
        )

        assertNull(
            CanonicalElementAffine.fromCanonicalOrNull(
                listOf(
                    1e308, 0.0, 0.0,
                    1e308, 0.0, 0.0
                )
            )
        )
    }

    @Test
    fun nonFiniteProjectionIsRejected() {
        val transform = affine(
            1.0, 0.0, 0.0, 1.0, 0.0, 0.0
        )

        assertNull(
            CanonicalStrokeProjection.pagePointOrNull(
                DuPoint(Double.POSITIVE_INFINITY, 4.0),
                transform
            )
        )

        val overflowing = affine(
            1.0, 0.0, 0.0, 1.0, 1e308, 0.0
        )

        assertNull(
            CanonicalStrokeProjection.pagePointOrNull(
                DuPoint(1e308, 0.0),
                overflowing
            )
        )

        val page = PositionedPage(
            "p", 612.0, 792.0, 0.0, 0.0
        )

        val invalidViewport = ViewportTransform(
            scalePxPerDu = 0.0,
            translationXPx = 0.0,
            translationYPx = 0.0
        )

        assertNull(
            CanonicalStrokeProjection.viewportPointOrNull(
                DuPoint(10.0, 10.0),
                transform,
                page,
                invalidViewport
            )
        )
    }
}
