package com.notebook.app.data.assets

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I3 format-compatibility evidence, NOT a revised JPEG acceptance policy.
 * All three embedded JPEG files are genuine and fully decoded by an independent
 * JPEG implementation before inclusion. The multi-scan input uses three
 * sequential baseline SOS scans (not progressive). The progressive uses SOF2.
 * Current strict policy intentionally fails closed on the latter two formats.
 * Do not interpret passing tests as approval to exclude either format from V1.
 */
@RunWith(AndroidJUnit4::class)
class RegisteredFinalizedImageDecoderJpegCompatibilityAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var root: File
    private var db: AppDatabase? = null

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "sto08-c2c3b-jpeg-compat-$suffix.sqlite"
        root = File(context.filesDir, "sto08-c2c3b-jpeg-compat-$suffix")
        assertTrue("Cannot create compatibility-test root", root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, databaseName)
    }

    @After fun tearDown() {
        db?.close()
        db = null
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
        if (::root.isInitialized && root.name.startsWith("sto08-c2c3b-jpeg-compat-")) {
            root.deleteRecursively()
        }
    }

    private fun liveDb(): AppDatabase = requireNotNull(db)

    private val baselineJpeg by lazy {
        Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAIBAQEBAQIBAQECAgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBw" +
            "YGCAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYHCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoK" +
            "CgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAAFAAcDAREAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAw" +
            "QFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkK" +
            "FhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmq" +
            "KjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEB" +
            "AQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRob" +
            "HBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE" +
            "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+P" +
            "n6/9oADAMBAAIRAxEAPwD4v/Yt/Z48Ef2dF+7/AOWJ/wCWY9PrWPFvD2C9o/U9XhjNcTyI/9k=",
            Base64.NO_WRAP,
        )
    }

    private val progressiveJpeg by lazy {
        Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIx" +
            "wcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIy" +
            "MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wgARCAAFAAcDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xA" +
            "AVAQEBAAAAAAAAAAAAAAAAAAACA//aAAwDAQACEAMQAAABig0//8QAFxAAAwEAAAAAAAAAAAAAAAAAAAIDBP/aAAgB" +
            "AQABBQLPJT//xAAXEQEAAwAAAAAAAAAAAAAAAAABAAIR/9oACAEDAQE/AaLk/8QAGBEAAgMAAAAAAAAAAAAAAAAAAA" +
            "ECAxL/2gAIAQIBAT8BsitH/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQAGPwJ//8QAFRABAQAAAAAAAAAAAAAA" +
            "AAAAABH/2gAIAQEAAT8hmf/aAAwDAQACAAMAAAAQA//EABYRAAMAAAAAAAAAAAAAAAAAAAARIf/aAAgBAwEBPxBbT/" +
            "/EABYRAAMAAAAAAAAAAAAAAAAAAAARIf/aAAgBAgEBPxBlD//EABgQAAIDAAAAAAAAAAAAAAAAAAARIUFR/9oACAEB" +
            "AAE/EFMVh//Z",
            Base64.NO_WRAP,
        )
    }

    private val multiscanJpeg by lazy {
        Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDg" +
            "sLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
            "FBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAAFAAcDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAw" +
            "QFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkK" +
            "FhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmq" +
            "KjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/9oACAEBAAA/ANb9" +
            "lf8AZ28NfYovk/5Zn/lmPT61/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBA" +
            "QAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZH" +
            "SElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExc" +
            "bHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oACAECEQA/AOTOMBhfrk/3aP/aAAgBAxEAPwDoybMcX9Sh" +
            "+8Z//9k=",
            Base64.NO_WRAP,
        )
    }

    private suspend fun register(bytes: ByteArray): AssetEntity =
        ManagedAssetRegistrar(ManagedAssetFileStore(root), liveDb().assetDao())
            .register(ByteArrayInputStream(bytes), "image/jpeg")

    private fun requireRealAndroidDecodableJpeg(bytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("Genuine JPEG fixture cannot be decoded by Android BitmapFactory", bitmap)
        try {
            assertEquals(7, requireNotNull(bitmap).width)
            assertEquals(5, bitmap.height)
        } finally {
            bitmap?.recycle()
        }
    }

    private suspend fun assertOriginalUnchanged(asset: AssetEntity, bytes: ByteArray) {
        assertArrayEquals(bytes, File(root, asset.relativePath).readBytes())
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals(1, liveDb().assetDao().countAll())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
        assertEquals("released", AssetAcquisitionLeaseCoordinator(root).withLease { "released" })
    }

    private suspend fun assertCurrentlyUnsupported(bytes: ByteArray, format: String) {
        requireRealAndroidDecodableJpeg(bytes)
        val asset = register(bytes)
        val result = runCatching {
            RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
        }
        assertTrue(
            "COMPATIBILITY GATE: valid $format JPEG must currently fail closed until " +
                "the accepted-format contract is reviewed; observed=" +
                result.fold({ "ACCEPTED $it" }, { "${it::class.java.name}: ${it.message}" }),
            result.exceptionOrNull() is IOException,
        )
        assertOriginalUnchanged(asset, bytes)
    }

    @Test fun genuineBaselineSingleScanIsAccepted() = runBlocking {
        requireRealAndroidDecodableJpeg(baselineJpeg)
        val asset = register(baselineJpeg)
        val decoded = RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
        assertEquals(asset.id, decoded.assetId)
        assertEquals("image/jpeg", decoded.mimeType)
        assertEquals(7, decoded.width)
        assertEquals(5, decoded.height)
        assertEquals(asset.sha256, decoded.sha256)
        assertOriginalUnchanged(asset, baselineJpeg)
    }

    @Test fun genuineProgressiveJpegIsCurrentlyRejectedFailClosed() = runBlocking {
        assertCurrentlyUnsupported(progressiveJpeg, "progressive SOF2")
    }

    @Test fun genuineBaselineMultiScanJpegIsCurrentlyRejectedFailClosed() = runBlocking {
        assertCurrentlyUnsupported(multiscanJpeg, "baseline three-scan")
    }
}
