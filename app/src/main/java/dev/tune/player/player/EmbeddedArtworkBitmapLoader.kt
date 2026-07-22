package dev.tune.player.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * Supplies the notification, lockscreen and media control centre with cover art.
 *
 * The artwork URI on each queue item points at the **audio file**, not at an image, because that
 * is the only artwork source that reliably exists: the picture is embedded in the file's own tags.
 * This loader recognises those URIs and pulls the embedded picture out.
 *
 * The previous approach — `content://media/external/audio/albumart/<albumId>` — is a legacy
 * MediaStore path that no longer resolves on modern Android, so nothing was ever displayed. It
 * also could not have worked in general: the system UI reads that URI from its own process, and
 * has no grant for ours.
 *
 * Songs with no embedded picture produce no bitmap, and the system falls back to the app icon —
 * matching the in-app placeholder rather than showing a stale cover from another track.
 */
@UnstableApi
class EmbeddedArtworkBitmapLoader : BitmapLoader, Closeable {

    // Extraction is blocking file I/O. One thread is plenty — only the current item is ever
    // requested, and CacheBitmapLoader wraps this so repeats don't hit the disk again.
    private val executor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit<Bitmap> {
            decodeScaled(data) ?: error("Artwork data could not be decoded")
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        executor.submit<Bitmap> {
            val path = uri.path ?: error("Artwork URI has no path: $uri")
            val bytes = embeddedPicture(path) ?: error("No embedded artwork in $path")
            decodeScaled(bytes) ?: error("Embedded artwork could not be decoded")
        }

    private fun embeddedPicture(path: String): ByteArray? {
        if (!File(path).isFile) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.embeddedPicture
        } catch (_: Exception) {
            // A malformed or unreadable file should show no art, not crash playback.
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Covers are routinely far larger than a notification icon, so the bitmap is subsampled on the
     * way in rather than decoded at full size and thrown away.
     */
    private fun decodeScaled(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)

        var sampleSize = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / sampleSize > TARGET_SIZE_PX * 2) sampleSize *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        const val TARGET_SIZE_PX = 512
    }
}
