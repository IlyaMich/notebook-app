package com.notebook.app.ui.editor.ink

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only the debug demo uses this database; it never opens production library.sqlite. */
internal class InkPersistenceDemoViewModel : ViewModel() {
    companion object {
        const val SESSION_EXTRA = "com.notebook.app.ink_demo_session"
        private const val DEFAULT_SESSION = "user"
        internal const val DOCUMENT_WIDTH_DU = 595.276 // Matches InitialPageSpec.a4PortraitBlank()
        private val SESSION_PATTERN = Regex("[a-zA-Z0-9-]{1,48}")
    }

    internal data class DisplayedStroke(
        val elementId: String,
        val canonical: CanonicalInkStroke,
        val saved: Boolean,
    )

    val strokes = mutableStateListOf<DisplayedStroke>()
    var status by mutableStateOf("Opening debug database...")
        private set
    var isReady by mutableStateOf(false)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    var revision by mutableStateOf(0L)
        private set

    val savedCount: Int get() = strokes.count { it.saved }
    val pendingCount: Int get() = strokes.count { !it.saved }

    private var started = false
    private lateinit var appContext: Context
    private lateinit var databaseName: String
    private lateinit var preferencesName: String
    private var database: AppDatabase? = null
    private var noteId: String? = null
    private var pageId: String? = null
    private val pending = ArrayDeque<DisplayedStroke>()
    private var worker: Job? = null
    private var reloading = false

    fun start(context: Context, requestedSession: String?) {
        if (started) return // retained ViewModel on rotation: never reset in-flight work
        started = true
        val session = requestedSession ?: DEFAULT_SESSION
        require(SESSION_PATTERN.matches(session)) { "Invalid debug demo session key" }
        appContext = context.applicationContext
        databaseName = "edt05-ink-demo-$session.sqlite"
        preferencesName = "edt05-ink-demo-$session"
        reloadFromDisk()
    }

    private data class Loaded(
        val database: AppDatabase,
        val noteId: String,
        val pageId: String,
        val revision: Long,
        val strokes: List<StoredCanonicalInkStroke>,
    )

    private suspend fun loadFreshDatabase(): Loaded = withContext(Dispatchers.IO) {
        // Force actual disk reopening. This is not an in-memory Room cache round-trip.
        database?.close()
        database = null
        val opened = AppDatabaseProvider.openIsolated(appContext, databaseName)
        try {
            val prefs = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            val storedNote = prefs.getString("noteId", null)
            val storedPage = prefs.getString("pageId", null)
            val (targetNoteId, targetPageId) = if (storedNote == null && storedPage == null) {
                val note = LibraryRepositoryFactory(opened).noteLifecycle().createNote(
                    notebookId = null,
                    title = "EDT-05 persistent ink demo",
                    firstPage = InitialPageSpec.a4PortraitBlank(),
                )
                val page = RoomDocumentRepository(opened).load(note.id).pages.single()
                check(prefs.edit().putString("noteId", note.id)
                    .putString("pageId", page.id).commit()) {
                    "Could not store debug demo note reference"
                }
                note.id to page.id
            } else {
                require(storedNote != null && storedPage != null) {
                    "Incomplete saved debug note reference; refusing to overwrite"
                }
                storedNote to storedPage
            }
            val repo = RoomDocumentRepository(opened)
            val content = repo.load(targetNoteId)
            check(content.pages.any { it.id == targetPageId }) {
                "Saved debug page is missing; refusing to overwrite"
            }
            val loadedStrokes = InkDocumentRepositoryBridge(repo)
                .loadSupportedStrokes(targetNoteId, targetPageId)
            Loaded(opened, targetNoteId, targetPageId, content.revision, loadedStrokes)
        } catch (failure: Exception) {
            opened.close()
            throw failure
        }
    }

    /** Explicit disk reopen is disabled with unsaved or in-flight input. */
    fun reloadFromDisk() {
        if (!started || reloading || isSaving || pending.isNotEmpty()) return
        reloading = true
        isReady = false
        status = "Reloading from Room..."
        viewModelScope.launch {
            try {
                val loaded = loadFreshDatabase()
                database = loaded.database
                noteId = loaded.noteId
                pageId = loaded.pageId
                revision = loaded.revision
                strokes.clear()
                strokes.addAll(loaded.strokes.map { DisplayedStroke(it.elementId, it.stroke, true) })
                saveError = null
                status = "Saved revision $revision (reloaded from disk)"
                isReady = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                saveError = failure.message ?: failure.javaClass.simpleName
                status = "Reload failed: $saveError"
            } finally {
                reloading = false
            }
        }
    }

    fun captureNative(native: androidx.ink.strokes.Stroke, canvasWidthPx: Int) {
        if (!isReady || canvasWidthPx <= 0) return
        try {
            val scale = InkPixelScale(canvasWidthPx / DOCUMENT_WIDTH_DU)
            val canonical = JetpackInkProbeAdapter.capture(native, scale)
            // Reject unrenderable results BEFORE enqueueing them as a committed mutation.
            JetpackInkProbeAdapter.rebuild(canonical, scale)
            submitCanonical(canonical)
        } catch (failure: Exception) {
            saveError = failure.message ?: failure.javaClass.simpleName
            status = "Capture failed: $saveError"
        }
    }

    /** Also used by instrumentation to simulate a completed native capture. */
    internal fun submitCanonical(
        canonical: CanonicalInkStroke,
        elementId: String = UUID.randomUUID().toString(),
    ) {
        check(isReady) { "The debug document is not ready" }
        val record = DisplayedStroke(elementId, canonical, false)
        strokes.add(record) // visible but NOT labeled as saved
        pending.addLast(record)
        if (saveError == null) drainPending()
    }

    fun retryPending() {
        if (!isReady || isSaving || pending.isEmpty()) return
        saveError = null
        drainPending()
    }

    /** One serialized queue: each successful completed stroke is exactly one Room command. */
    private fun drainPending() {
        if (!isReady || reloading || worker?.isActive == true || pending.isEmpty()) return
        val db = checkNotNull(database)
        val targetNote = checkNotNull(noteId)
        val targetPage = checkNotNull(pageId)
        worker = viewModelScope.launch {
            isSaving = true
            try {
                while (pending.isNotEmpty()) {
                    val item = pending.first()
                    status = "Saving ${pending.size} stroke(s)..."
                    val commit = withContext(Dispatchers.IO) {
                        InkDocumentRepositoryBridge(RoomDocumentRepository(db)).insertCompletedStroke(
                            targetNote, targetPage, item.elementId, item.canonical,
                        )
                    }
                    // Only a completed durable commit can transition this item to saved.
                    revision = commit.revision
                    val index = strokes.indexOfFirst { it.elementId == item.elementId && !it.saved }
                    if (index >= 0) strokes[index] = item.copy(saved = true)
                    pending.removeFirst()
                }
                saveError = null
                status = "Saved revision $revision"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                saveError = failure.message ?: failure.javaClass.simpleName
                status = "Save failed — pending stroke retained; Retry to try again"
            } finally {
                isSaving = false
            }
        }
    }

    override fun onCleared() {
        // The demo's database is private to this ViewModel, not AppDatabaseProvider.get().
        database?.close()
        database = null
        super.onCleared()
    }
}
