package com.notebook.app.data.assets

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit negative URI preflight; never dispatches the picker or writes any
 * external provider. Real provider persistence remains a separate acceptance gate.
 */
@RunWith(AndroidJUnit4::class)
class ExportSafCreatedDocumentDestinationAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rawFileUriCannotEnterCreatedDocumentDestination() {
        assertNotNull(runCatching {
            ExportSafCreatedDocumentDestination(
                context, Uri.parse("file:///sdcard/Downloads/private.notebook"),
            )
        }.exceptionOrNull())
    }

    @Test fun arbitraryContentUriWithoutSafDocumentIdentityCannotEnter() {
        assertNotNull(runCatching {
            ExportSafCreatedDocumentDestination(
                context, Uri.parse("content://com.example.unrelated/not-a-document"),
            )
        }.exceptionOrNull())
    }
}
