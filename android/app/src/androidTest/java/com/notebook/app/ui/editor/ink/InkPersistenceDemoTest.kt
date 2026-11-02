package com.notebook.app.ui.editor.ink

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** EDT-05 CP2C: real debug Activity + real on-disk Room, without touching library.sqlite. */
@RunWith(AndroidJUnit4::class)
class InkPersistenceDemoTest {
    private lateinit var context: Context
    private lateinit var sessionKey: String
    private val activeScenarios = mutableListOf<ActivityScenario<InkPersistenceDemoActivity>>()

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        sessionKey = "test-${UUID.randomUUID()}"
        context.deleteDatabase("edt05-ink-demo-$sessionKey.sqlite")
        context.getSharedPreferences("edt05-ink-demo-$sessionKey", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun tearDown() {
        activeScenarios.forEach { it.close() }
        activeScenarios.clear()
        context.deleteDatabase("edt05-ink-demo-$sessionKey.sqlite")
        context.getSharedPreferences("edt05-ink-demo-$sessionKey", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun launch(): ActivityScenario<InkPersistenceDemoActivity> {
        val intent = Intent(context, InkPersistenceDemoActivity::class.java)
            .putExtra(InkPersistenceDemoViewModel.SESSION_EXTRA, sessionKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch<InkPersistenceDemoActivity>(intent)
            .also { activeScenarios.add(it) }
    }

    private data class Snapshot(
        val ready: Boolean,
        val saving: Boolean,
        val saved: Int,
        val pending: Int,
        val revision: Long,
        val status: String,
        val strokes: List<CanonicalInkStroke>,
        val hasError: Boolean,
    )

    private fun snapshot(scenario: ActivityScenario<InkPersistenceDemoActivity>): Snapshot {
        var value: Snapshot? = null
        scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[InkPersistenceDemoViewModel::class.java]
            value = Snapshot(
                vm.isReady, vm.isSaving, vm.savedCount, vm.pendingCount,
                vm.revision, vm.status, vm.strokes.map { it.canonical }, vm.saveError != null,
            )
        }
        return checkNotNull(value)
    }

    private fun await(
        scenario: ActivityScenario<InkPersistenceDemoActivity>,
        message: String,
        predicate: (Snapshot) -> Boolean,
    ): Snapshot {
        repeat(120) {
            val state = snapshot(scenario)
            if (predicate(state)) return state
            Thread.sleep(100)
        }
        fail("Timed out: $message; last state=${snapshot(scenario)}")
        throw AssertionError("unreachable")
    }

    private fun submit(
        scenario: ActivityScenario<InkPersistenceDemoActivity>,
        stroke: CanonicalInkStroke,
        elementId: String = UUID.randomUUID().toString(),
    ) {
        scenario.onActivity { activity ->
            ViewModelProvider(activity)[InkPersistenceDemoViewModel::class.java]
                .submitCanonical(stroke, elementId)
        }
    }

    private fun stroke(offset: Double = 0.0) = CanonicalInkStroke(
        brush = CanonicalInkBrush("pen", 3.0, "#2255AA", 1.0),
        points = listOf(
            CanonicalInkPoint(20.0 + offset, 30.0, 0, pressure = 0.4),
            CanonicalInkPoint(48.0 + offset, 39.0, 15, pressure = 0.7),
        ),
    )

    @Test fun completedCapturePersistsAcrossFullActivityFinishAndRelaunch() {
        val first = launch()
        await(first, "initial demo load") { it.ready }
        val original = stroke()
        submit(first, original)
        val afterCommit = await(first, "durable stroke commit") {
            it.ready && !it.saving && it.saved == 1 && it.pending == 0
        }
        assertEquals(1L, afterCommit.revision)
        assertFalse(afterCommit.hasError)
        assertTrue(afterCommit.status.startsWith("Saved revision 1"))
        first.close()
        activeScenarios.remove(first)

        // New Activity and ViewModel; the stroke must be loaded from SQLite + canonical JSON.
        val reopened = launch()
        val recovered = await(reopened, "reopening the persisted demo") {
            it.ready && it.saved == 1
        }
        assertEquals(listOf(original), recovered.strokes)
        assertEquals(1L, recovered.revision)
        assertEquals(0, recovered.pending)
        assertTrue(recovered.status.contains("reloaded from disk"))
    }

    @Test fun explicitReloadRebuildsFromDatabaseRatherThanOnlyRetainingState() {
        val activity = launch()
        await(activity, "initial load") { it.ready }
        val original = stroke(5.0)
        submit(activity, original)
        await(activity, "save") { it.saved == 1 && it.pending == 0 && !it.saving }
        activity.onActivity {
            ViewModelProvider(it)[InkPersistenceDemoViewModel::class.java].reloadFromDisk()
        }
        val recovered = await(activity, "explicit database reopen") {
            it.ready && it.status.contains("reloaded from disk")
        }
        assertEquals(1L, recovered.revision)
        assertEquals(listOf(original), recovered.strokes)
    }

    @Test fun failedDuplicateInsertNeverReportsFailedStrokeAsSaved() {
        val activity = launch()
        await(activity, "initial load") { it.ready }
        val sameId = UUID.randomUUID().toString()
        submit(activity, stroke(), sameId)
        await(activity, "first save") { it.saved == 1 && it.pending == 0 && !it.saving }
        submit(activity, stroke(15.0), sameId)
        val failed = await(activity, "duplicate-id rejection") {
            it.hasError && !it.saving && it.pending == 1
        }
        assertEquals(1L, failed.revision)
        assertEquals(1, failed.saved)
        assertTrue(failed.status.startsWith("Save failed"))
        assertEquals(2, failed.strokes.size) // unsaved sample remains visible in memory
    }

    @Test fun rapidCompletedStrokesAreSerializedIntoDistinctRevisions() {
        val activity = launch()
        await(activity, "initial load") { it.ready }
        val expected = (0..2).map { stroke(it * 10.0) }
        expected.forEach { submit(activity, it) }
        val state = await(activity, "three queued saves") {
            !it.saving && it.pending == 0 && it.saved == 3
        }
        assertEquals(3L, state.revision)
        assertEquals(expected, state.strokes)
    }
}
