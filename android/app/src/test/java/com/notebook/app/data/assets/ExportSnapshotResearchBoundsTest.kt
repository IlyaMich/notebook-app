package com.notebook.app.data.assets

import java.lang.Math.addExact
import java.lang.Math.multiplyExact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * STO-08 C3-A2-I3-B B1: pure JVM research fixture accounting.
 * These constants are PROVISIONAL test assumptions, never export authorization.
 * This test does not access Room, filesystem, devices, or production exports.
 */
class ExportSnapshotResearchBoundsTest {
    private object ResearchCeilings {
        const val MAX_PAGES = 32
        const val MAX_ELEMENTS = 2_048
        const val MAX_DISTINCT_IMAGES = 16
        const val MAX_SOURCE_BYTES = 64L * 1024L * 1024L
        const val MAX_CANONICAL_JSON_BYTES = 8L * 1024L * 1024L
        const val MAX_MANIFEST_BYTES = 1L * 1024L * 1024L
        const val MAX_STAGING_BYTES = 160L * 1024L * 1024L
        const val MAX_COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_IMAGE_BYTES = 16L * 1024L * 1024L
    }

    private data class Source(val assetId: String, val byteCount: Long, val mime: String)
    private data class Plan(
        val pageCount: Int,
        val elementCount: Int,
        val canonicalJsonBytes: Long,
        val manifestBytes: Long,
        val copyBufferBytes: Int,
        val stagingBytes: Long,
        val ownerEdges: List<Source>,
    )

    /** Model-only accounting: distinct copies, but all owner edges stay represented. */
    private fun calculateDistinctSourceBytes(plan: Plan): Long {
        require(plan.pageCount in 1..ResearchCeilings.MAX_PAGES)
        require(plan.elementCount in 0..ResearchCeilings.MAX_ELEMENTS)
        require(plan.canonicalJsonBytes in 0..ResearchCeilings.MAX_CANONICAL_JSON_BYTES)
        require(plan.manifestBytes in 0..ResearchCeilings.MAX_MANIFEST_BYTES)
        require(plan.copyBufferBytes in 1..ResearchCeilings.MAX_COPY_BUFFER_BYTES)
        require(plan.stagingBytes in 0..ResearchCeilings.MAX_STAGING_BYTES)

        val distinct = linkedMapOf<String, Source>()
        for (edge in plan.ownerEdges) {
            require(edge.assetId.isNotBlank())
            require(edge.mime == "image/png" || edge.mime == "image/jpeg")
            require(edge.byteCount in 1..ResearchCeilings.MAX_IMAGE_BYTES)
            val previous = distinct.putIfAbsent(edge.assetId, edge)
            require(previous == null || previous == edge) { "Conflicting metadata for one asset ID" }
            require(distinct.size <= ResearchCeilings.MAX_DISTINCT_IMAGES)
        }
        var bytes = 0L
        for (source in distinct.values) {
            bytes = addExact(bytes, source.byteCount)
            require(bytes <= ResearchCeilings.MAX_SOURCE_BYTES)
        }
        return bytes
    }

    private fun plan(vararg edges: Source) = Plan(
        pageCount = 1, elementCount = edges.size, canonicalJsonBytes = 128,
        manifestBytes = 256, copyBufferBytes = 64 * 1024, stagingBytes = 512,
        ownerEdges = edges.toList(),
    )

    @Test fun mediaFreeControlHasZeroCopiedBytes() {
        assertEquals(0L, calculateDistinctSourceBytes(plan()))
    }

    @Test fun duplicateOwnerEdgesCountOnceWithoutDiscardingOwnerOccurrences() {
        val shared = Source("asset-a", 1024L, "image/png")
        val owners = listOf(shared, shared, Source("asset-b", 512L, "image/jpeg"))
        assertEquals(3, owners.size)
        assertEquals(1536L, calculateDistinctSourceBytes(plan(*owners.toTypedArray())))
    }

    @Test fun exactBoundarySixteenImagesAndSixtyFourMiBAcceptedAsResearchFixture() {
        val edges = (0 until 16).map {
            Source("asset-$it", 4L * 1024L * 1024L, "image/png")
        }
        assertEquals(64L * 1024L * 1024L, calculateDistinctSourceBytes(plan(*edges.toTypedArray())))
    }

    @Test fun seventeenthDistinctImageRejects() {
        val edges = (0 until 17).map { Source("asset-$it", 1L, "image/png") }
        assertThrows(IllegalArgumentException::class.java) {
            calculateDistinctSourceBytes(plan(*edges.toTypedArray()))
        }
    }

    @Test fun mismatchedMetadataForSharedAssetRejects() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateDistinctSourceBytes(plan(Source("x", 3L, "image/png"), Source("x", 4L, "image/png")))
        }
    }

    @Test fun oversizeOrUnsupportedIndividualSourceRejects() {
        listOf(
            Source("x", 0L, "image/png"),
            Source("x", 16L * 1024L * 1024L + 1L, "image/png"),
            Source("x", 1L, "application/pdf"),
        ).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) {
                calculateDistinctSourceBytes(plan(bad))
            }
        }
    }

    @Test fun aggregateOverSixtyFourMiBRejectsEvenWhenImagesIndividuallyValid() {
        val edges = (0 until 5).map {
            Source("asset-$it", 16L * 1024L * 1024L, "image/jpeg")
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateDistinctSourceBytes(plan(*edges.toTypedArray()))
        }
    }

    @Test fun pageElementJsonManifestBufferAndStagingBoundariesRejectOverLimit() {
        val valid = plan()
        val invalid = listOf(
            valid.copy(pageCount = 0),
            valid.copy(pageCount = 33),
            valid.copy(elementCount = 2_049),
            valid.copy(canonicalJsonBytes = 8L * 1024L * 1024L + 1L),
            valid.copy(manifestBytes = 1L * 1024L * 1024L + 1L),
            valid.copy(copyBufferBytes = 65_537),
            valid.copy(stagingBytes = 160L * 1024L * 1024L + 1L),
            valid.copy(stagingBytes = -1L),
        )
        invalid.forEach { p ->
            assertThrows(IllegalArgumentException::class.java) { calculateDistinctSourceBytes(p) }
        }
    }

    @Test fun checkedArithmeticRejectsLongOverflowRatherThanWrapping() {
        assertThrows(ArithmeticException::class.java) { addExact(Long.MAX_VALUE, 1L) }
        assertThrows(ArithmeticException::class.java) { multiplyExact(Long.MAX_VALUE, 2L) }
    }
}
