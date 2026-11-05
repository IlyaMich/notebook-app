package com.notebook.app.data.assets

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedAssetRecoveryInventoryTest {
    @get:Rule val temp = TemporaryFolder()

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0,
    )

    @Test fun validFinalFromStageOneIsRecognizedWithoutMutation() {
        val finalized = ManagedAssetFileStore(temp.root, directorySync = {})
            .stageAndFinalize(ByteArrayInputStream(png), "image/png")
        val before = File(temp.root, finalized.relativePath).readBytes()
        val found = ManagedAssetRecoveryInventory(temp.root).scan()
        assertEquals(listOf(ManagedAssetRecoveryInventory.Finding(
            finalized.relativePath, ManagedAssetRecoveryInventory.Kind.VALID_FINAL)), found)
        assertArrayEquals(before, File(temp.root, finalized.relativePath).readBytes())
    }

    @Test fun leftoverPartialIsReportedButNeverDeleted() {
        val stage = File(temp.root, "staging").apply { mkdir() }
        val partial = File(stage, "asset-orphan.partial").apply { writeBytes(png) }
        val found = ManagedAssetRecoveryInventory(temp.root).scan()
        assertEquals(ManagedAssetRecoveryInventory.Kind.STAGING_FILE, found.single().kind)
        assertArrayEquals(png, partial.readBytes())
    }

    @Test fun incompletePublishedFileIsFlaggedAndPreserved() {
        val finalized = ManagedAssetFileStore(temp.root, directorySync = {})
            .stageAndFinalize(ByteArrayInputStream(png), "image/png")
        val finalFile = File(temp.root, finalized.relativePath)
        val truncated = png.copyOfRange(0, 4)
        finalFile.writeBytes(truncated)
        val found = ManagedAssetRecoveryInventory(temp.root).scan()
        assertEquals(ManagedAssetRecoveryInventory.Kind.HASH_MISMATCH, found.single().kind)
        assertArrayEquals(truncated, finalFile.readBytes())
    }

    @Test fun validHashButWrongSignatureIsFlagged() {
        val bytes = byteArrayOf(10, 11, 12)
        val hash = sha256(bytes)
        val file = File(temp.root, "assets/${hash.take(2)}/${hash.drop(2).take(2)}/$hash.png")
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
        assertEquals(ManagedAssetRecoveryInventory.Kind.SIGNATURE_MISMATCH,
            ManagedAssetRecoveryInventory(temp.root).scan().single().kind)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun unexpectedAssetEntryIsFlaggedWithoutDeletion() {
        val assets = File(temp.root, "assets").apply { mkdir() }
        val odd = File(assets, "unrecognized.bin").apply { writeText("retain") }
        assertEquals(ManagedAssetRecoveryInventory.Kind.UNEXPECTED_ENTRY,
            ManagedAssetRecoveryInventory(temp.root).scan().single().kind)
        assertTrue(odd.isFile)
    }

    @Test fun scanLimitFailsClosedAndLeavesEverythingUntouched() {
        val stage = File(temp.root, "staging").apply { mkdir() }
        repeat(3) { File(stage, "asset-$it.partial").writeText("live-or-abandoned") }
        try {
            ManagedAssetRecoveryInventory(temp.root, maximumEntries = 2).scan()
            fail("Expected a bounded-scan error")
        } catch (_: IOException) {
            // No deletion even when inventory cannot be completed.
        }
        assertEquals(3, stage.listFiles().orEmpty().size)
    }

    @Test fun oversizedFinalIsNotReadOrModified() {
        val hash = "a".repeat(64)
        val file = File(temp.root, "assets/aa/aa/$hash.png")
        file.parentFile!!.mkdirs()
        file.writeBytes(png)
        val finding = ManagedAssetRecoveryInventory(temp.root, maximumHashBytesPerFile = 4).scan().single()
        assertEquals(ManagedAssetRecoveryInventory.Kind.OVERSIZED_FINAL, finding.kind)
        assertArrayEquals(png, file.readBytes())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
