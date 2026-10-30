package com.notebook.app.ui.editor.ink

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression for CP1B rotation: canonical data survives Activity recreation only. */
@RunWith(AndroidJUnit4::class)
class InkProbeRecreationTest {
    @get:Rule val composeRule = createAndroidComposeRule<InkProbeActivity>()

    @Test
    fun completedCanonicalStrokeSurvivesRecreationAndCanBeCleared() {
        val expected = CanonicalInkStroke(
            brush = CanonicalInkBrush("pen", 3.0, "#2255AA", 1.0),
            points = listOf(
                CanonicalInkPoint(10.0, 20.0, 0L, pressure = 0.4),
                CanonicalInkPoint(40.0, 25.0, 15L, pressure = 0.7),
            ),
        )
        var initialSession: InkProbeSessionViewModel? = null
        composeRule.waitForIdle()
        composeRule.activityRule.scenario.onActivity { activity ->
            initialSession = ViewModelProvider(activity)[InkProbeSessionViewModel::class.java]
            initialSession!!.canonicalStrokes.add(expected)
        }
        composeRule.onNodeWithText("1 finished strokes").assertExists()

        // ActivityScenario.recreate() exercises an actual configuration-style recreation.
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("1 finished strokes").assertExists()
        composeRule.activityRule.scenario.onActivity { activity ->
            val retained = ViewModelProvider(activity)[InkProbeSessionViewModel::class.java]
            assertSame(initialSession, retained)
            assertEquals(listOf(expected), retained.canonicalStrokes.toList())
        }

        // Clear must erase retained canonical state, not only the disposable render cache.
        composeRule.onNodeWithText("Clear").performClick()
        composeRule.onNodeWithText("0 finished strokes").assertExists()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("0 finished strokes").assertExists()
    }
}
