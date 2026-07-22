package dev.tune.player.art

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What to draw for a song, album or artist.
 *
 * [overridePath] is a user-supplied image; when present it always wins. Otherwise the artwork
 * embedded in [audioPath] is used. [version] exists purely so that replacing an override
 * invalidates the cached image.
 */
data class ArtRequest(
    val overridePath: String?,
    val audioPath: String?,
    val version: String = overridePath.orEmpty(),
) {
    val isEmpty: Boolean get() = overridePath == null && audioPath == null
}

/** Cache key — two requests with the same sources should hit the same cached bitmap. */
class ArtKeyer : Keyer<ArtRequest> {
    override fun key(data: ArtRequest, options: Options): String =
        "art:${data.overridePath.orEmpty()}|${data.audioPath.orEmpty()}|${data.version}"
}

class ArtFetcher(
    private val data: ArtRequest,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val bytes = data.overridePath?.let { path ->
            runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()
        } ?: data.audioPath?.let(::embeddedPicture) ?: return@withContext null

        val bitmap = decodeScaled(bytes, requestedPixels()) ?: return@withContext null
        DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    /** Pulls the cover art out of the audio file's tags (ID3 APIC, FLAC PICTURE, MP4 covr). */
    private fun embeddedPicture(path: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.embeddedPicture
        } catch (_: Exception) {
            // Unreadable or tagless file — the UI falls back to a placeholder.
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Covers are frequently far larger than the view showing them, so decode bounds first and
     * subsample. Without this, a grid of 1400x1400 covers will churn through memory.
     */
    private fun decodeScaled(bytes: ByteArray, targetPx: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        // Compare the *longest* side: with `&&` a wide cover stopped subsampling as soon as its
        // short side fit, so a 3000x500 image decoded at full width. Matches the notification
        // loader's calculation — these two must agree or one of them is wrong.
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > targetPx) {
            sample *= 2
        }

        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode) }.getOrNull()
    }

    private fun requestedPixels(): Int {
        fun Dimension.pixels() = (this as? Dimension.Pixels)?.px
        return maxOf(
            options.size.width.pixels() ?: DEFAULT_TARGET_PX,
            options.size.height.pixels() ?: DEFAULT_TARGET_PX,
        ).coerceIn(MIN_TARGET_PX, MAX_TARGET_PX)
    }

    class Factory : Fetcher.Factory<ArtRequest> {
        override fun create(data: ArtRequest, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.isEmpty) null else ArtFetcher(data, options)
    }

    private companion object {
        const val DEFAULT_TARGET_PX = 512
        const val MIN_TARGET_PX = 96
        const val MAX_TARGET_PX = 1_024
    }
}
