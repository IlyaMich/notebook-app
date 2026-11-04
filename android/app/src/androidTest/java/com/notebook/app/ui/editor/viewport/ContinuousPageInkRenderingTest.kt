
package com.notebook.app.ui.editor.viewport

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class ContinuousPageInkRenderingTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun persistedStrokeAppearsInsideContinuousViewport() {
        val element = StoredElement(
            id = "stroke-1",
            orderIndex = 0L,
            kind = "stroke",
            envelopeJson = """
                {
                  "id": "stroke-1",
                  "type": "stroke",
                  "transform": [1,0,0,1,0,0],
                  "locked": false,
                  "payload": {
                    "encoding": "sampledPointsV1",
                    "brush": {
                      "kind": "pen",
                      "width": 8,
                      "color": "#FF0000",
                      "opacity": 1.0
                    },
                    "points": [
                      {"x":20,"y":50,"tMs":0},
                      {"x":80,"y":50,"tMs":20}
                    ]
                  }
                }
            """.trimIndent(),
            revision = 1L,
        )

        val page = StoredPage(
            id = "page-1",
            orderIndex = 0L,
            width = 100.0,
            height = 100.0,
            backgroundJson =
                """{"type":"blank","color":"#FFFFFF"}""",
            revision = 1L,
            elements = listOf(element),
        )

        rule.setContent {
            Box(Modifier.size(300.dp)) {
                ContinuousPageViewport(
                    pages = listOf(
                        PageViewportItem(
                            id = "page-1",
                            widthDu = 100.0,
                            heightDu = 100.0,
                            elementCount = 1,
                        )
                    ),
                    documentKey = "ink-render-test",
                    storedPages = mapOf(page.id to page),
                )
            }
        }

        rule.waitForIdle()

        val image = rule
            .onNodeWithContentDescription(
                "Read-only note viewport with 1 pages"
            )
            .captureToImage()

        val pixels = image.toPixelMap()
        val center = pixels[
            image.width / 2,
            image.height / 2
        ]

        // The horizontal red stroke passes through the
        // center of the fitted page.
        assertTrue(
            "Expected a visible red canonical stroke at page center",
            center.red > 0.8f &&
                center.green < 0.3f &&
                center.blue < 0.3f
        )
    }

    @Test
    fun refreshedPageSnapshotUpdatesVisiblePaperColor() {
        val white = """{"type":"blank","color":"#FFFFFF"}"""
        val warm = """{"type":"blank","color":"#FFF9E9"}"""

        val page = StoredPage(
            id = "page-background",
            orderIndex = 0L,
            width = 100.0,
            height = 100.0,
            backgroundJson = white,
            revision = 1L,
            elements = emptyList(),
        )

        val currentPage = mutableStateOf(page)

        rule.setContent {
            Box(Modifier.size(300.dp)) {
                ContinuousPageViewport(
                    pages = listOf(
                        PageViewportItem(
                            id = page.id,
                            widthDu = page.width,
                            heightDu = page.height,
                            elementCount = 0,
                        )
                    ),
                    documentKey = "background-update-test",
                    storedPages = mapOf(
                        page.id to currentPage.value
                    ),
                )
            }
        }

        rule.waitForIdle()

        fun centerPixel() = rule
            .onNodeWithContentDescription(
                "Read-only note viewport with 1 pages"
            )
            .captureToImage()
            .let { image ->
                image.toPixelMap()[
                    image.width / 2,
                    image.height / 2,
                ]
            }

        val initial = centerPixel()
        assertTrue(initial.red > 0.98f)
        assertTrue(initial.green > 0.98f)
        assertTrue(initial.blue > 0.98f)

        // Simulate the editor receiving a refreshed
        // committed Room snapshot after an autosave.
        rule.runOnIdle {
            currentPage.value = page.copy(
                backgroundJson = warm,
                revision = 2L,
            )
        }

        rule.waitForIdle()

        val refreshed = centerPixel()

        assertTrue(refreshed.red > 0.98f)
        assertTrue(refreshed.green > 0.95f)
        assertTrue(refreshed.blue < 0.94f)

        assertEquals(white, page.backgroundJson)
        assertEquals(1L, page.revision)
    }

    @Test
    fun refreshedStrokeSnapshotInvalidatesDecodedCache() {
        fun stroke(
            color: String,
            revision: Long,
        ) = StoredElement(
            id = "stroke-refresh",
            orderIndex = 0L,
            kind = "stroke",
            envelopeJson = """
                {
                  "id": "stroke-refresh",
                  "type": "stroke",
                  "transform": [1,0,0,1,0,0],
                  "locked": false,
                  "payload": {
                    "encoding": "sampledPointsV1",
                    "brush": {
                      "kind": "pen",
                      "width": 8,
                      "color": "$color",
                      "opacity": 1
                    },
                    "points": [
                      {"x":20,"y":50,"tMs":0},
                      {"x":80,"y":50,"tMs":20}
                    ]
                  }
                }
            """.trimIndent(),
            revision = revision,
        )

        val originalPage = StoredPage(
            id = "page-refresh",
            orderIndex = 0L,
            width = 100.0,
            height = 100.0,
            backgroundJson =
                """{"type":"blank","color":"#FFFFFF"}""",
            revision = 1L,
            elements = listOf(stroke("#FF0000", 1L)),
        )

        val originalJson =
            originalPage.elements.single().envelopeJson

        val refreshedPage = originalPage.copy(
            revision = 2L,
            elements = listOf(stroke("#0000FF", 2L)),
        )

        val currentPage = mutableStateOf(originalPage)

        rule.setContent {
            Box(Modifier.size(300.dp)) {
                ContinuousPageViewport(
                    pages = listOf(
                        PageViewportItem(
                            id = originalPage.id,
                            widthDu = 100.0,
                            heightDu = 100.0,
                            elementCount = 1,
                        )
                    ),
                    documentKey = "stroke-refresh-test",
                    storedPages = mapOf(
                        originalPage.id to currentPage.value
                    ),
                )
            }
        }

        rule.waitForIdle()

        fun centerColor() = rule
            .onNodeWithContentDescription(
                "Read-only note viewport with 1 pages"
            )
            .captureToImage()
            .let { image ->
                image.toPixelMap()[
                    image.width / 2,
                    image.height / 2,
                ]
            }

        val before = centerColor()

        assertTrue(
            "Expected original red stroke",
            before.red > 0.8f &&
                before.green < 0.3f &&
                before.blue < 0.3f
        )

        // Simulate the editor receiving a new confirmed
        // Room snapshot without navigating away.
        rule.runOnIdle {
            currentPage.value = refreshedPage
        }

        rule.waitForIdle()

        val after = centerColor()

        assertTrue(
            "Expected refreshed blue stroke",
            after.blue > 0.8f &&
                after.red < 0.3f &&
                after.green < 0.3f
        )

        // The old canonical snapshot must remain intact.
        assertEquals(
            originalJson,
            originalPage.elements.single().envelopeJson,
        )
        assertEquals(1L, originalPage.revision)
        assertEquals(2L, refreshedPage.revision)
    }

    @Test
    fun malformedStoredStrokeDoesNotCrashViewport() {
        val element = StoredElement(
            id = "corrupt-stroke",
            orderIndex = 0L,
            kind = "stroke",
            envelopeJson = "{invalid-json",
            revision = 1L,
        )

        val page = StoredPage(
            id = "corrupt-page",
            orderIndex = 0L,
            width = 100.0,
            height = 100.0,
            backgroundJson =
                """{"type":"blank","color":"#FFFFFF"}""",
            revision = 1L,
            elements = listOf(element),
        )

        rule.setContent {
            Box(Modifier.size(300.dp)) {
                ContinuousPageViewport(
                    pages = listOf(
                        PageViewportItem(
                            id = page.id,
                            widthDu = page.width,
                            heightDu = page.height,
                            elementCount = 1,
                        )
                    ),
                    documentKey = "corrupt-stroke-test",
                    storedPages = mapOf(page.id to page),
                )
            }
        }

        rule.waitForIdle()

        val image = rule
            .onNodeWithContentDescription(
                "Read-only note viewport with 1 pages"
            )
            .captureToImage()

        assertTrue(image.width > 0)
        assertTrue(image.height > 0)
    }
}
