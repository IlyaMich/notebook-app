package com.notebook.app.data.assets

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.entity.ElementAssetReferenceEntity
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageAssetReferenceEntity
import com.notebook.app.data.local.entity.PageEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 C3-A2-I3-C B3-A: ISOLATED, UNTRUSTED snapshot research only.
 *
 * This is not an export API, an ownership-completeness audit, a verified-asset
 * reader or a durable-copy probe. No source image bytes are opened or copied.
 * Every fixture is a uniquely named on-disk Room database; library.sqlite and
 * the production file store must never be opened. Test authoring is separate
 * from authorization to compile or run instrumentation on an Android device.
 */
@RunWith(AndroidJUnit4::class)
class ExportSnapshotResearchRoomAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "sto08-c3a2-room-${UUID.randomUUID()}.sqlite"
        check(databaseName.matches(Regex("sto08-c3a2-room-[0-9a-f-]{36}\\.sqlite")))
        check(databaseName != AppDatabaseProvider.DATABASE_NAME)
        // Never delete an existing database as part of setup.
        check(!context.getDatabasePath(databaseName).exists())
        db = AppDatabaseProvider.openIsolated(context, databaseName)
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::databaseName.isInitialized) {
            check(databaseName.matches(Regex("sto08-c3a2-room-[0-9a-f-]{36}\\.sqlite")))
            check(databaseName != AppDatabaseProvider.DATABASE_NAME)
            assertTrue("Isolated test DB cleanup failed", context.deleteDatabase(databaseName))
        }
    }

    private fun id(): String = UUID.randomUUID().toString()
    private val blank = """{"type":"blank","color":"#ffffff"}"""

    private fun imageJson(elementId: String, assetId: String) =
        """{"id":"$elementId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""

    private fun textJson(elementId: String) =
        """{"id":"$elementId","type":"text","transform":[1,0,0,1,0,0],"locked":false,"payload":{"text":"hello","frame":{"x":0,"y":0,"width":10,"height":10},"style":{}}}"""

    private data class Owner(val notebookId: String, val noteId: String, val pageId: String)

    private suspend fun createOwner(): Owner {
        val notebookId = id()
        val noteId = id()
        val pageId = id()
        db.notebookDao().insert(NotebookEntity(notebookId, null, "B3-A isolated", sortOrder = 0L,
            createdAtMillis = 1L, updatedAtMillis = 1L))
        db.noteDao().insert(NoteEntity(noteId, notebookId, "B3-A note", 1L, 1L, revision = 1L))
        db.pageDao().insert(PageEntity(pageId, noteId, 0L, 612.0, 792.0, blank, 1L))
        return Owner(notebookId, noteId, pageId)
    }

    private suspend fun addElement(owner: Owner, assetId: String? = null): String {
        val elementId = id()
        val kind = if (assetId == null) "text" else "image"
        val json = if (assetId == null) textJson(elementId) else imageJson(elementId, assetId)
        val index = db.elementDao().countByPage(owner.pageId).toLong()
        db.elementDao().insert(ElementEntity(elementId, owner.pageId, index, kind, json, 1L))
        return elementId
    }

    private suspend fun addSyntheticAsset(mime: String = "image/png"): String {
        val assetId = id()
        // These are synthetic *metadata only*. No real image exists at this path.
        val sha = (id() + id()).replace("-", "")
        val ext = if (mime == "image/jpeg") "jpg" else "png"
        db.assetDao().insert(AssetEntity(assetId, sha, mime, 10L,
            "assets/${sha.take(2)}/${sha.substring(2, 4)}/$sha.$ext", "finalized"))
        return assetId
    }

    private data class ImageOccurrence(val elementId: String, val assetId: String)
    private data class ResearchObservation(
        val noteRevision: Long,
        val pageIds: List<String>,
        val elementIds: List<String>,
        val occurrences: List<ImageOccurrence>,
        val distinctSourceIds: Set<String>,
    )

    /** All dependent reads occur within exactly one Room transaction. */
    private suspend fun observe(noteId: String): ResearchObservation = db.withTransaction {
        val note = requireNotNull(db.noteDao().findById(noteId)) { "Note is missing" }
        require(note.deletedAtMillis == null) { "Trashed note" }
        require(note.revision >= 0L) { "Invalid note revision" }
        val notebook = requireNotNull(db.notebookDao().findById(note.notebookId)) {
            "Parent notebook is missing"
        }
        require(notebook.deletedAtMillis == null) { "Trashed notebook" }
        // A folder ancestor needs separate recursive checking; this first slice
        // deliberately rejects nonroot notebooks instead of asserting safety.
        require(notebook.parentFolderId == null) { "Folder ancestry not supported by B3-A" }

        val pages = db.pageDao().listByNote(noteId)
        require(pages.isNotEmpty() && pages.size <= 32) { "Page count outside research range" }
        val elementIds = mutableListOf<String>()
        val occurrences = mutableListOf<ImageOccurrence>()
        val sources = linkedSetOf<String>()
        var count = 0
        pages.forEachIndexed { pageIndex, page ->
            require(page.noteId == note.id && page.orderIndex == pageIndex.toLong()) {
                "Page order/owner mismatch"
            }
            require(page.revision in 0L..note.revision && page.width > 0 && page.height > 0) {
                "Invalid page geometry/revision"
            }
            require(DocumentAssetReferenceInspector.pageBackground(page.backgroundJson) ==
                DocumentAssetReferenceInspector.Finding.NoDirectReference) {
                "Unsupported or unresolved page background"
            }
            // This first-scope export prohibits PDF and every page asset edge.
            require(db.assetOwnershipDao().findPageReference(page.id) == null) {
                "Unexpected page asset edge"
            }
            val elements = db.elementDao().listByPage(page.id)
            count += elements.size
            require(count <= 2048) { "Element count outside research range" }
            elements.forEachIndexed { elementIndex, element ->
                require(element.pageId == page.id && element.orderIndex == elementIndex.toLong()) {
                    "Element order/owner mismatch"
                }
                require(element.revision in 0L..note.revision) { "Invalid element revision" }
                val parsed = Json.parseToJsonElement(element.elementJson) as? JsonObject
                    ?: error("Invalid element JSON object")
                val jsonId = (parsed["id"] as? JsonPrimitive)?.content
                val jsonType = (parsed["type"] as? JsonPrimitive)?.content
                require(jsonId == element.id && jsonType == element.kind) {
                    "Element envelope/row identity mismatch"
                }
                val finding = DocumentAssetReferenceInspector.element(element.elementJson)
                val edge = db.assetOwnershipDao().findElementReference(element.id)
                when (finding) {
                    is DocumentAssetReferenceInspector.Finding.Reference -> {
                        require(element.kind == "image" && edge?.assetId == finding.assetId) {
                            "Missing/mismatched image edge"
                        }
                        val asset = requireNotNull(db.assetDao().findById(finding.assetId)) {
                            "Image asset metadata missing"
                        }
                        require(validSyntheticMetadata(asset)) { "Invalid finalized image metadata" }
                        occurrences += ImageOccurrence(element.id, asset.id)
                        sources += asset.id
                        require(sources.size <= 16) { "Image count outside research range" }
                    }
                    DocumentAssetReferenceInspector.Finding.NoDirectReference -> {
                        require(element.kind != "image" && edge == null) {
                            "Phantom edge or missing image reference"
                        }
                    }
                    is DocumentAssetReferenceInspector.Finding.Unresolved ->
                        error("Unresolved canonical element: ${finding.reason}")
                }
                elementIds += element.id
            }
        }
        ResearchObservation(note.revision, pages.map { it.id }.toList(),
            elementIds.toList(), occurrences.toList(), sources.toSet())
    }

    private fun validSyntheticMetadata(asset: AssetEntity): Boolean {
        if (asset.state != "finalized" || asset.byteSize <= 0L || asset.byteSize > 16L * 1024 * 1024) return false
        val ext = when (asset.mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            else -> return false
        }
        if (!asset.sha256.matches(Regex("[0-9a-f]{64}"))) return false
        return asset.relativePath ==
            "assets/${asset.sha256.take(2)}/${asset.sha256.substring(2, 4)}/${asset.sha256}.$ext"
    }

    private suspend fun rejects(noteId: String) {
        var rejected = false
        try {
            observe(noteId)
        } catch (_: IllegalArgumentException) {
            rejected = true
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue("Research observation must fail closed", rejected)
    }

    @Test fun r01MediaFreeActiveNoteHasNoImageSources() = runBlocking {
        val owner = createOwner()
        addElement(owner)
        val snapshot = observe(owner.noteId)
        assertEquals(1L, snapshot.noteRevision)
        assertEquals(listOf(owner.pageId), snapshot.pageIds)
        assertEquals(1, snapshot.elementIds.size)
        assertTrue(snapshot.occurrences.isEmpty())
        assertTrue(snapshot.distinctSourceIds.isEmpty())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun r02SingleImageWithMatchingTypedEdgeIsObserved() = runBlocking {
        val owner = createOwner()
        val asset = addSyntheticAsset()
        val element = addElement(owner, asset)
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(element, asset))
        val snapshot = observe(owner.noteId)
        assertEquals(listOf(ImageOccurrence(element, asset)), snapshot.occurrences)
        assertEquals(setOf(asset), snapshot.distinctSourceIds)
    }

    @Test fun r03TwoOwnersShareOneSourceButKeepTwoOccurrences() = runBlocking {
        val owner = createOwner()
        val asset = addSyntheticAsset("image/jpeg")
        repeat(2) {
            val element = addElement(owner, asset)
            db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(element, asset))
        }
        val snapshot = observe(owner.noteId)
        assertEquals(2, snapshot.occurrences.size)
        assertEquals(setOf(asset), snapshot.distinctSourceIds)
        assertEquals(2, snapshot.occurrences.map { it.elementId }.toSet().size)
    }

    @Test fun r04MissingImageEdgeFailsClosed() = runBlocking {
        val owner = createOwner()
        addElement(owner, addSyntheticAsset())
        rejects(owner.noteId)
    }

    @Test fun r05PhantomEdgeOnTextFailsClosed() = runBlocking {
        val owner = createOwner()
        val element = addElement(owner)
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(element, addSyntheticAsset()))
        rejects(owner.noteId)
    }

    @Test fun r06MismatchedImageEdgeFailsClosed() = runBlocking {
        val owner = createOwner()
        val referencedAsset = addSyntheticAsset()
        val otherAsset = addSyntheticAsset()
        val element = addElement(owner, referencedAsset)
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(element, otherAsset))
        rejects(owner.noteId)
    }

    @Test fun r07UnexpectedPageEdgeOrPdfBackgroundFailsClosed() = runBlocking {
        val owner = createOwner()
        addElement(owner)
        val asset = addSyntheticAsset()
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(owner.pageId, asset))
        rejects(owner.noteId)
        // A separate isolated owner probes unsupported PDF background without a page edge.
        val pdfOwner = createOwner()
        val page = requireNotNull(db.pageDao().findById(pdfOwner.pageId))
        val pdf = page.copy(backgroundJson =
            """{"type":"pdfPage","assetId":"$asset","pageIndex":0,"displayBox":"crop"}""")
        assertEquals(1, db.pageDao().update(pdf))
        rejects(pdfOwner.noteId)
    }

    @Test fun r08TrashedNoteOrNotebookFailsClosed() = runBlocking {
        val owner = createOwner()
        addElement(owner)
        val note = requireNotNull(db.noteDao().findById(owner.noteId))
        assertEquals(1, db.noteDao().update(note.copy(deletedAtMillis = 2L)))
        rejects(owner.noteId)
        val second = createOwner()
        addElement(second)
        val notebook = requireNotNull(db.notebookDao().findById(second.notebookId))
        assertEquals(1, db.notebookDao().update(notebook.copy(deletedAtMillis = 2L)))
        rejects(second.noteId)
    }

    @Test fun r09MalformedHiddenOrMismatchedCanonicalEnvelopeFailsClosed() = runBlocking {
        val owner = createOwner()
        val element = addElement(owner)
        val original = requireNotNull(db.elementDao().findById(element))
        val variants = listOf(
            "not json",
            textJson(element).replace("\"text\":\"hello\"", "\"text\":\"hello\",\"assetId\":\"${id()}\""),
            textJson(id()),
            textJson(element).replace("\"type\":\"text\"", "\"type\":\"future\""),
        )
        for (raw in variants) {
            assertEquals(1, db.elementDao().update(original.copy(elementJson = raw)))
            rejects(owner.noteId)
        }
        assertEquals(1, db.elementDao().update(original))
        assertFalse(observe(owner.noteId).elementIds.isEmpty())
    }
}
