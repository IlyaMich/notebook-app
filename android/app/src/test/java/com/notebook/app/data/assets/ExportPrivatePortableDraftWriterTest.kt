package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** This verifies a PRIVATE draft only. No import, publication, receipt or GC. */
class ExportPrivatePortableDraftWriterTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun id() = UUID.randomUUID().toString()
    private val limits = ExportPrivateStageLimits(4, 4096, 8192, 4096, 8)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class Fixture(
        val root: File,
        val observed: ExportUntrustedPrivateStageObservation,
        val bytes: ByteArray,
    ) {
        fun stageDir() = File(root, "pending-export-copies/" + observed.operationId)
    }

    private suspend fun fixture(): Fixture {
        val root = tmp.newFolder()
        val op = id()
        val note = id()
        val book = id()
        val page = id()
        val element = id()
        val asset = id()
        val bytes = byteArrayOf(0x42, 0x51, 0x14, 0x02)
        val digest = sha(bytes)
        val envelope = """{"id":"@E@","type":"image","transform":[1,0,0,1,0,0],
            "locked":false,"payload":{"assetId":"@A@",
            "frame":{"x":0,"y":0,"width":2,"height":3},"opacity":1}}"""
            .replace("@E@", element).replace("@A@", asset)
        val snapshot = SnapshotResult.Complete(
            note = SnapshotNote(note, book, "Portable test", 1000L, 2000L, null, 2L),
            pages = listOf(SnapshotPage(page, note, 0L, 595.0, 842.0,
                """{"type":"blank","color":"#FFFFFF"}""", 1L,
                listOf(SnapshotElement(element, page, 0L, "image", envelope, 1L)))),
            imageOwners = listOf(SnapshotImageOwner(note, page, element, asset)),
            assets = listOf(SnapshotAssetMetadata(asset, digest, "image/png", bytes.size.toLong(),
                "assets/" + digest.take(2) + "/" + digest.substring(2, 4) + "/" + digest + ".png",
                "finalized")),
            canonicalJsonBytes = 200L, totalEncodedSourceBytes = bytes.size.toLong(),
        )
        val stage = ExportRetainedPrivateStageStore(root, limits, syncDirectory = {})
            .createNew(op)
        val snapBytes = ExportCapturedDocumentCodec.snapshot(op, snapshot)
        val snapshotDigest = stage.writeCapturedSnapshot(snapBytes)
        val copy = stage.copyAsset(
            ExpectedEncodedImage(asset, "image/png", digest, bytes.size.toLong()), bytes)
        val manifestBytes = ExportCapturedDocumentCodec.manifest(
            op, snapshot, snapshotDigest, listOf(copy))
        stage.writeCopyManifest(manifestBytes)
        return Fixture(root, stage.observeUntrusted(note, 2L, listOf(asset)), bytes)
    }

    @Test fun draftContainsPortableManifestPagesAndIndependentAssetBytes() = runBlocking {
        val f = fixture()
        val draft = ExportPrivatePortableDraftWriter(f.root, limits, syncDirectory = {})
            .writeDraft(f.observed)
        assertEquals(f.observed.operationId, draft.operationId)
        assertEquals(f.observed.noteId, draft.noteId)
        assertEquals("draft.notebook", draft.fileName)
        assertTrue(draft.byteCount > f.bytes.size)
        val stageDir = f.stageDir()
        val draftFile = File(stageDir, "draft.notebook")
        assertEquals(draft.sha256, sha(draftFile.readBytes()))
        ZipFile(draftFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(3, names.size)
            assertEquals(names.size, names.distinct().size)
            assertTrue("manifest.json" in names)
            val manifest = Json.parseToJsonElement(
                zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()
            ).jsonObject
            assertEquals(1, manifest.getValue("schemaVersion").jsonPrimitive.int)
            assertEquals(f.observed.noteId, manifest.getValue("id").jsonPrimitive.content)
            val pagePath = manifest.getValue("pages").jsonArray.single().jsonObject
                .getValue("path").jsonPrimitive.content
            assertTrue(pagePath.startsWith("pages/"))
            val page = Json.parseToJsonElement(
                zip.getInputStream(zip.getEntry(pagePath)).bufferedReader().readText()
            ).jsonObject
            assertEquals("image", page.getValue("elements").jsonArray.single()
                .jsonObject.getValue("type").jsonPrimitive.content)
            val assetPath = manifest.getValue("assets").jsonArray.single()
                .jsonObject.getValue("path").jsonPrimitive.content
            assertArrayEquals(f.bytes,
                zip.getInputStream(zip.getEntry(assetPath)).use { it.readBytes() })
        }
        assertEquals(draft,
            ExportPrivatePortableDraftWriter(f.root, limits, syncDirectory = {})
                .inspectExistingDraftReadOnly(f.observed))
        assertFalse(File(stageDir, "receipt").exists())
        assertFalse(File(f.root, "assets").exists())
        assertArrayEquals(f.bytes, File(stageDir, f.observed.copies.single().fileName).readBytes())
    }

    @Test fun corruptedStageCannotCreateDraft() = runBlocking {
        val f = fixture()
        File(f.stageDir(), f.observed.copies.single().fileName)
            .writeBytes(f.bytes.reversedArray())
        assertTrue(runCatching {
            ExportPrivatePortableDraftWriter(f.root, limits, syncDirectory = {})
                .writeDraft(f.observed)
        }.exceptionOrNull() is IOException)
        assertFalse(File(f.stageDir(), "draft.notebook").exists())
    }

    @Test fun failingDirectorySyncLeavesPartialDraftWithoutSuccessOrDeletion() = runBlocking {
        val f = fixture()
        assertTrue(runCatching {
            ExportPrivatePortableDraftWriter(f.root, limits, syncDirectory = {
                throw IOException("Synthetic fsync fault")
            }).writeDraft(f.observed)
        }.exceptionOrNull() is IOException)
        assertTrue(File(f.stageDir(), "draft.notebook").exists())
        assertArrayEquals(f.bytes,
            File(f.stageDir(), f.observed.copies.single().fileName).readBytes())
    }

    @Test fun tamperedPrivateDraftAfterRestartCannotMatch() = runBlocking {
        val f = fixture()
        ExportPrivatePortableDraftWriter(f.root, limits, syncDirectory = {})
            .writeDraft(f.observed)
        val file = File(f.stageDir(), "draft.notebook")
        file.writeBytes(file.readBytes().reversedArray())
        assertTrue(runCatching {
            ExportPrivatePortableDraftWriter(f.root, limits, syncDirectory = {})
                .inspectExistingDraftReadOnly(f.observed)
        }.exceptionOrNull() is IOException)
        assertArrayEquals(f.bytes,
            File(f.stageDir(), f.observed.copies.single().fileName).readBytes())
    }

    @Test fun preexistingDraftCannotBeOverwrittenOrAdopted() = runBlocking {
        val f = fixture()
        val target = File(f.stageDir(), "draft.notebook")
        target.writeText("do not overwrite")
        assertTrue(runCatching {
            ExportPrivatePortableDraftWriter(f.root, limits, syncDirectory = {})
                .writeDraft(f.observed)
        }.exceptionOrNull() is IOException)
        assertEquals("do not overwrite", target.readText())
    }
}
