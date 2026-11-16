package com.notebook.app.data.assets

import com.notebook.app.data.local.dao.AssetDao
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Observed decoded dimensions and verified supplied bytes only.
 * NOT a media-origin certificate, protected export receipt, or ownership authorization.
 */
internal data class VerifiedDecodedImageInfo(
    val assetId: String,
    val mimeType: String,
    val sha256: String,
    val encodedBytes: Long,
    val width: Int,
    val height: Int,
    val pixels: Long,
)

/**
 * Standalone registered-image inspector. Obtains the single publication lease through
 * RegisteredFinalizedImageBytesReader; do NOT call from an already-held outer lease.
 * Future export copying calls BoundedImageBytesInspector directly with independently
 * bound, exact destination bytes while its coordinator already holds the outer lease.
 * No raw byte array, Bitmap, open file or mutable path escapes inspection.
 */
internal class RegisteredFinalizedImageDecoder(
    privateFilesDir: File,
    assetDao: AssetDao,
) {
    private val source = RegisteredFinalizedImageBytesReader(privateFilesDir, assetDao)

    suspend fun inspect(assetId: String): VerifiedDecodedImageInfo =
        withInspectedAssetLease(assetId) { it }

    /**
     * Preserve the existing lease through the entire suspending callback. The verified
     * bytes reader clears the owned temporary buffer after callback completion/failure.
     * This callback must not reacquire the non-reentrant lease or call an asset publisher.
     */
    suspend fun <T> withInspectedAssetLease(
        assetId: String,
        action: suspend (VerifiedDecodedImageInfo) -> T,
    ): T = withContext(Dispatchers.IO) {
        source.withVerifiedBytes(assetId) { snapshot, bytes ->
            currentCoroutineContext().ensureActive()
            val info = BoundedImageBytesInspector.inspect(
                expected = ExpectedEncodedImage(
                    assetId = snapshot.assetId,
                    mimeType = snapshot.mimeType,
                    sha256 = snapshot.sha256,
                    encodedBytes = snapshot.byteSize,
                ),
                exactBytes = bytes,
            )
            currentCoroutineContext().ensureActive()
            action(info)
        }
    }
}
