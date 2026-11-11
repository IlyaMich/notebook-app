
package com.notebook.app.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryWindowLayoutTest {

    @Test
    fun portraitPhoneUsesCompactLayout() {
        assertEquals(
            LibraryWindowLayout.COMPACT,
            libraryWindowLayout(400.dp, 850.dp),
        )
    }

    @Test
    fun wideButShortLandscapePhoneUsesCompactLayout() {
        assertEquals(
            LibraryWindowLayout.COMPACT,
            libraryWindowLayout(900.dp, 390.dp),
        )
    }

    @Test
    fun wideTabletUsesExpandedLayout() {
        assertEquals(
            LibraryWindowLayout.EXPANDED,
            libraryWindowLayout(1280.dp, 800.dp),
        )
    }

    @Test
    fun narrowTabletUsesCompactLayout() {
        assertEquals(
            LibraryWindowLayout.COMPACT,
            libraryWindowLayout(750.dp, 1000.dp),
        )
    }

    @Test
    fun shortWideWindowUsesCompactLayout() {
        assertEquals(
            LibraryWindowLayout.COMPACT,
            libraryWindowLayout(1100.dp, 400.dp),
        )
    }

    @Test
    fun exactWidthAndHeightThresholdsUseExpandedLayout() {
        assertEquals(
            LibraryWindowLayout.EXPANDED,
            libraryWindowLayout(840.dp, 480.dp),
        )
    }

    @Test
    fun oneDpBelowWidthThresholdUsesCompactLayout() {
        assertEquals(
            LibraryWindowLayout.COMPACT,
            libraryWindowLayout(839.dp, 800.dp),
        )
    }

    @Test
    fun oneDpBelowHeightThresholdUsesCompactLayout() {
        assertEquals(
            LibraryWindowLayout.COMPACT,
            libraryWindowLayout(1000.dp, 479.dp),
        )
    }
}
