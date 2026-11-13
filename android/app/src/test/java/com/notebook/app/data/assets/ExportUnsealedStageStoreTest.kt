package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic JVM fixture only. Never uses the app database or managed originals. */
class ExportUnsealedStageStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fixture(): File = temp.newFolder("private-${UUID.randomUUID()}")
    private fun policy(
        snapshot: Long = 128,
        manifest: Long = 128,
        aggregate: Long = 256,
        stages: Int = 4,
    ) = ExportUnsealedStagePolicy(snapshot, manifest, aggregate, stages)

    private fun store(
        root: File,
        limits: ExportUnsealedStagePolicy = policy(),
        sync: (File) -> Unit = {},
        forced: (java.nio.channels.FileChannel) -> Unit = { it.force(true) },
        chunk: (String, Long) -> Unit = { _, _ -> },
        id: () -> UUID = { UUID.randomUUID() },
    ) = ExportUnsealedStageStore(root, limits, sync, forced, chunk, id)

    private fun stageDir(root: File, id: UUID): File =
        File(root, "export-source-snapshots/$id")

    private fun digest(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun expectIo(action: () -> Unit) {
        try { action() } catch (_: IOException) { return }
        fail("Expected IOException")
    }

    @Test fun freshStageWritesOnlyTwoFixedSyntheticFilesAndNoSealOrAssetTree() {
        val root = fixture()
        val stage = store(root).createFreshStage()
        val raw = "synthetic canonical fixture".toByteArray(Charsets.UTF_8)
        val manifest = byteArrayOf(0, 1, 2, 3)
        val a = stage.writeSyntheticSnapshot(raw)
        val b = stage.writeSyntheticManifest(manifest)
        assertEquals("snapshot.bin", a.role)
        assertEquals(raw.size.toLong(), a.byteCount)
        assertEquals(digest(raw), a.sha256)
        assertEquals("manifest.bin", b.role)
        assertEquals(digest(manifest), b.sha256)
        val dir = stageDir(root, stage.operationId)
        assertEquals(setOf("snapshot.bin", "manifest.bin"),
            dir.listFiles().orEmpty().map { it.name }.toSet())
        assertArrayEquals(raw, File(dir, "snapshot.bin").readBytes())
        assertArrayEquals(manifest, File(dir, "manifest.bin").readBytes())
        assertFalse(File(dir, "seal").exists())
        assertFalse(File(root, "assets").exists())
        assertFalse(File(root, ".asset-publication.lock").exists())
    }

    @Test fun exactIndependentBudgetsPassAndOneByteOverRejectsWithoutSuccess() {
        val root = fixture()
        val s = store(root, policy(snapshot = 3, manifest = 2, aggregate = 5)).createFreshStage()
        assertEquals(3L, s.writeSyntheticSnapshot(byteArrayOf(1, 2, 3)).byteCount)
        assertEquals(2L, s.writeSyntheticManifest(byteArrayOf(4, 5)).byteCount)
        val overRoot = fixture()
        val over = store(overRoot, policy(snapshot = 3, manifest = 2, aggregate = 5)).createFreshStage()
        expectIo { over.writeSyntheticSnapshot(byteArrayOf(1, 2, 3, 4)) }
        assertFalse(File(stageDir(overRoot, over.operationId), "snapshot.bin").exists())
        expectIo { over.writeSyntheticManifest(byteArrayOf(1)) } // failed stage is tainted
        val aggRoot = fixture()
        val agg = store(aggRoot, policy(snapshot = 4, manifest = 4, aggregate = 5)).createFreshStage()
        agg.writeSyntheticSnapshot(byteArrayOf(1, 2, 3, 4))
        expectIo { agg.writeSyntheticManifest(byteArrayOf(5, 6)) }
        assertFalse(File(stageDir(aggRoot, agg.operationId), "manifest.bin").exists())
    }

    @Test fun existingFixedFileNeverOverwrittenAndNoRetryAllowed() {
        val root = fixture()
        val stage = store(root).createFreshStage()
        val first = byteArrayOf(9, 8, 7)
        stage.writeSyntheticSnapshot(first)
        expectIo { stage.writeSyntheticSnapshot(byteArrayOf(2)) }
        assertArrayEquals(first, File(stageDir(root, stage.operationId), "snapshot.bin").readBytes())
    }

    @Test fun generatedUuidCollisionRejectedWithoutAdoptingExistingStage() {
        val root = fixture()
        val fixed = UUID.randomUUID()
        val st = store(root, id = { fixed })
        st.createFreshStage()
        val sentinel = File(stageDir(root, fixed), "sentinel.txt")
        sentinel.writeText("do not touch")
        expectIo { st.createFreshStage() }
        assertEquals("do not touch", sentinel.readText())
    }

    @Test fun streamedChunksAreBoundedAndInjectedWriteFailureRetainsUntrustedFile() {
        val root = fixture()
        val payload = ByteArray(8_193) { (it and 255).toByte() }
        val stage = store(
            root, policy(snapshot = 9_000, manifest = 2, aggregate = 9_002),
            chunk = { _, written -> if (written >= 8_192) throw IOException("synthetic write fault") },
        ).createFreshStage()
        expectIo { stage.writeSyntheticSnapshot(payload) }
        val partial = File(stageDir(root, stage.operationId), "snapshot.bin")
        assertTrue(partial.exists())
        assertTrue(partial.length() in 1L until payload.size.toLong())
        expectIo { stage.writeSyntheticManifest(byteArrayOf(1)) }
        assertFalse(File(stageDir(root, stage.operationId), "manifest.bin").exists())
    }

    @Test fun injectedFileForceFailureRetainsStageWithoutSuccess() {
        val root = fixture()
        val stage = store(root, forced = { throw IOException("synthetic force failure") })
            .createFreshStage()
        expectIo { stage.writeSyntheticSnapshot(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3),
            File(stageDir(root, stage.operationId), "snapshot.bin").readBytes())
        expectIo { stage.writeSyntheticManifest(byteArrayOf(1)) }
    }

    @Test fun injectedParentSyncFailureLeavesUntrustedStageAndAdmitsNoSuccess() {
        val root = fixture()
        val snapshots = File(root, "export-source-snapshots")
        val st = store(root, sync = { dir ->
            if (dir == snapshots) throw IOException("synthetic stage entry sync failure")
        })
        expectIo { st.createFreshStage() }
        assertEquals(1, snapshots.listFiles().orEmpty().size)
        assertFalse(File(root, "assets").exists())
    }

    @Test fun injectedFileEntrySyncFailureDoesNotAdmitNextFile() {
        val root = fixture()
        val st = store(root, sync = { dir ->
            if (dir.name.matches(Regex("[0-9a-f-]{36}"))) {
                throw IOException("synthetic entry sync failure")
            }
        })
        val stage = st.createFreshStage()
        expectIo { stage.writeSyntheticSnapshot(byteArrayOf(1, 2)) }
        assertTrue(File(stageDir(root, stage.operationId), "snapshot.bin").exists())
        expectIo { stage.writeSyntheticManifest(byteArrayOf(1)) }
    }

    @Test fun abandonedStageAdmissionLimitRejectsWithoutDeletingAnything() {
        val root = fixture()
        val st = store(root, policy(stages = 2))
        val a = st.createFreshStage()
        val b = st.createFreshStage()
        assertTrue(stageDir(root, a.operationId).exists())
        assertTrue(stageDir(root, b.operationId).exists())
        expectIo { st.createFreshStage() }
        assertEquals(2, File(root, "export-source-snapshots").listFiles().orEmpty().size)
    }

    @Test fun nonDirectoryStagingParentIsRejectedWithoutModification() {
        val root = fixture()
        val badParent = File(root, "export-source-snapshots")
        badParent.writeText("sentinel")
        expectIo { store(root).createFreshStage() }
        assertEquals("sentinel", badParent.readText())
    }

    @Test fun linkedRootIsRejectedWhenFilesystemAllowsSymlinkFixtures() {
        val realRoot = fixture()
        val link: Path = temp.root.toPath().resolve("root-alias")
        try {
            Files.createSymbolicLink(link, realRoot.toPath())
        } catch (notSupported: UnsupportedOperationException) {
            assumeNoException(notSupported)
        } catch (noPrivilege: java.nio.file.FileSystemException) {
            assumeNoException(noPrivilege)
        } catch (noPermission: SecurityException) {
            assumeNoException(noPermission)
        }
        assertTrue(Files.isSymbolicLink(link))
        assertFalse(Files.isDirectory(link, LinkOption.NOFOLLOW_LINKS))
        expectIo { store(link.toFile()).createFreshStage() }
        assertFalse(File(realRoot, "export-source-snapshots").exists())
    }

    @Test fun separateStagesHaveDistinctOperationIdsAndIndependentContents() {
        val root = fixture()
        val st = store(root)
        val a = st.createFreshStage()
        val b = st.createFreshStage()
        assertTrue(a.operationId != b.operationId)
        a.writeSyntheticSnapshot(byteArrayOf(1))
        b.writeSyntheticSnapshot(byteArrayOf(2))
        assertArrayEquals(byteArrayOf(1), File(stageDir(root, a.operationId), "snapshot.bin").readBytes())
        assertArrayEquals(byteArrayOf(2), File(stageDir(root, b.operationId), "snapshot.bin").readBytes())
    }
}
