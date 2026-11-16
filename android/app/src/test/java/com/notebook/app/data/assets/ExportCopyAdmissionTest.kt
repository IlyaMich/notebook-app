package com.notebook.app.data.assets

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Metadata fixtures only: no Room, managed originals, stages, or export activation. */
class ExportCopyAdmissionTest {
    private val digest = "ab" + "1f" + "c".repeat(60)
    private val asset = SnapshotAssetMetadata(
        id = "image-1", sha256 = digest, mimeType = "image/png", encodedBytes = 10,
        relativePath = "assets/ab/1f/$digest.png", state = "finalized",
    )
    private val owner = SnapshotImageOwner("note-1", "page-1", "element-1", "image-1")
    private val note = SnapshotNote("note-1", "book-1", "Example", 1, 1, null, 1)
    private val limits = ExportCopyAdmissionLimits(3, 100, 200)

    private fun snapshot(
        assets: List<SnapshotAssetMetadata> = listOf(asset),
        owners: List<SnapshotImageOwner> = listOf(owner),
        total: Long = assets.sumOf { it.encodedBytes },
    ) = SnapshotResult.Complete(
        note = note, pages = emptyList(), imageOwners = owners, assets = assets,
        canonicalJsonBytes = 0, totalEncodedSourceBytes = total,
    )

    @Test fun validMetadataIsCopiedIntoAnImmutableDetachedPlan() {
        val mutable = mutableListOf(asset)
        val plan = ExportCopyAdmission.plan(snapshot(assets = mutable), limits)
        mutable.clear()
        assertEquals(1, plan.images.size)
        assertEquals(10L, plan.totalEncodedBytes)
        assertThrows(UnsupportedOperationException::class.java) {
            (plan.images as MutableList).clear()
        }
    }

    @Test fun emptyImagesAreAllowedWhenTotalAndOwnersAreEmpty() {
        val plan = ExportCopyAdmission.plan(snapshot(emptyList(), emptyList(), 0), limits)
        assertTrue(plan.images.isEmpty())
        assertEquals(0L, plan.totalEncodedBytes)
    }

    @Test fun rejectWrongDigestPathMimeAndState() {
        val invalid = listOf(
            asset.copy(sha256 = "bad"), asset.copy(relativePath = "assets/wrong"),
            asset.copy(mimeType = "image/gif"), asset.copy(state = "staged"),
        )
        for (value in invalid) assertThrows(IOException::class.java) {
            ExportCopyAdmission.plan(snapshot(assets = listOf(value)), limits)
        }
    }

    @Test fun rejectUnownedAndUnknownImageIds() {
        assertThrows(IOException::class.java) {
            ExportCopyAdmission.plan(snapshot(owners = emptyList()), limits)
        }
        assertThrows(IOException::class.java) {
            ExportCopyAdmission.plan(snapshot(owners = listOf(owner.copy(assetId = "absent"))), limits)
        }
    }

    @Test fun rejectDuplicateIdsAndDuplicateOwnerEntries() {
        assertThrows(IOException::class.java) {
            ExportCopyAdmission.plan(snapshot(assets = listOf(asset, asset)), limits)
        }
        assertThrows(IOException::class.java) {
            ExportCopyAdmission.plan(snapshot(owners = listOf(owner, owner)), limits)
        }
    }

    @Test fun rejectTotalMismatchAndExceededLimits() {
        assertThrows(IOException::class.java) {
            ExportCopyAdmission.plan(snapshot(total = 9), limits)
        }
        assertThrows(IOException::class.java) {
            ExportCopyAdmission.plan(snapshot(), limits.copy(maxAssetBytes = 9))
        }
        assertThrows(IOException::class.java) {
            ExportCopyAdmission.plan(snapshot(), limits.copy(maxTotalBytes = 9))
        }
    }

    @Test fun rejectWrongNoteIdentityAndBlankOwnerFields() {
        assertThrows(IOException::class.java) {
            ExportCopyAdmission.plan(snapshot(owners = listOf(owner.copy(noteId = "other"))), limits)
        }
        assertThrows(IOException::class.java) {
            ExportCopyAdmission.plan(snapshot(owners = listOf(owner.copy(pageId = " "))), limits)
        }
    }
}
