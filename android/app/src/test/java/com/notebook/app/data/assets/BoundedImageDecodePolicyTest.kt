package com.notebook.app.data.assets

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedImageDecodePolicyTest {
    private fun reject(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected IOException; got $error", error is IOException)
    }

    @Test fun encodedByteBoundariesAreExact() {
        BoundedImageDecodePolicy.requireEncoded("image/png", 1L)
        BoundedImageDecodePolicy.requireEncoded("image/jpeg", 16_777_216L)
        reject { BoundedImageDecodePolicy.requireEncoded("image/png", 0L) }
        reject { BoundedImageDecodePolicy.requireEncoded("image/jpeg", -1L) }
        reject { BoundedImageDecodePolicy.requireEncoded("image/png", 16_777_217L) }
    }

    @Test fun onlyExactPngAndJpegMimeTypesAreAccepted() {
        reject { BoundedImageDecodePolicy.requireEncoded("application/pdf", 12L) }
        reject { BoundedImageDecodePolicy.requireEncoded("image/webp", 12L) }
        reject { BoundedImageDecodePolicy.requireEncoded("IMAGE/PNG", 12L) }
    }

    @Test fun exactEightMegapixelsAndThirtyTwoMibArePermitted() {
        assertEquals(
            BoundedImageDecodePolicy.PixelBudget(4096, 2048, 8_388_608, 33_554_432),
            BoundedImageDecodePolicy.requireIntrinsicDimensions(4096, 2048),
        )
        assertEquals(4L, BoundedImageDecodePolicy.requireIntrinsicDimensions(1, 1).argbBytes)
    }

    @Test fun widthHeightAndPixelLimitsAreIndependent() {
        reject { BoundedImageDecodePolicy.requireIntrinsicDimensions(4097, 1) }
        reject { BoundedImageDecodePolicy.requireIntrinsicDimensions(1, 4097) }
        reject { BoundedImageDecodePolicy.requireIntrinsicDimensions(4096, 2049) }
        reject { BoundedImageDecodePolicy.requireIntrinsicDimensions(4096, 4096) }
    }

    @Test fun invalidOrExtremeDimensionsFailBeforeAllocation() {
        reject { BoundedImageDecodePolicy.requireIntrinsicDimensions(0, 1) }
        reject { BoundedImageDecodePolicy.requireIntrinsicDimensions(1, -1) }
        reject { BoundedImageDecodePolicy.requireIntrinsicDimensions(Int.MAX_VALUE, Int.MAX_VALUE) }
    }

    @Test fun reportedBitmapByteCountsMustBePositiveConsistentAndBounded() {
        BoundedImageDecodePolicy.requireBitmapBytes(33_554_432L, 33_554_432L)
        reject { BoundedImageDecodePolicy.requireBitmapBytes(0L, 10L) }
        reject { BoundedImageDecodePolicy.requireBitmapBytes(20L, 19L) }
        reject { BoundedImageDecodePolicy.requireBitmapBytes(33_554_433L, 33_554_433L) }
        reject { BoundedImageDecodePolicy.requireBitmapBytes(4L, 33_554_433L) }
    }
}
