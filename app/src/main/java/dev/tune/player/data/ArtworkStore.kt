package dev.tune.player.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class OverridesFile(
    val albumArt: Map<String, String> = emptyMap(),
    val artistArt: Map<String, String> = emptyMap(),
)

/** An album or artist image the user picked, replacing whatever is embedded in the files. */
data class ArtworkOverrides(
    val albumArt: Map<Long, String> = emptyMap(),
    val artistArt: Map<Long, String> = emptyMap(),
)

/**
 * Custom album/artist images, kept entirely inside the app's own storage.
 *
 * Audio files are never rewritten: setting an image copies the picked file into `files/artwork/`
 * and records a pointer to it, so clearing an override restores the embedded art untouched.
 */
class ArtworkStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val indexFile = AtomicTextFile(context.filesDir.resolve("artwork-overrides.json"))
    private val artDir: File get() = File(context.filesDir, "artwork").apply { mkdirs() }

    private val _overrides = MutableStateFlow(ArtworkOverrides())
    val overrides: StateFlow<ArtworkOverrides> = _overrides.asStateFlow()

    /** Serialises the index read-modify-write so concurrent set/clear calls can't lose entries. */
    private val mutex = Mutex()

    suspend fun load() = withContext(Dispatchers.IO) {
        val parsed = if (indexFile.exists) {
            json.decodeFromString<OverridesFile>(indexFile.readText())
        } else OverridesFile()

        _overrides.value = ArtworkOverrides(
            albumArt = parsed.albumArt.mapNotNullKeysToLong(),
            artistArt = parsed.artistArt.mapNotNullKeysToLong(),
        )
        val referenced = (_overrides.value.albumArt.values + _overrides.value.artistArt.values).toSet()
        artDir.listFiles()?.filterNot { it.absolutePath in referenced }?.forEach(File::delete)
    }

    /** @return null on success, or a human-readable reason the image could not be saved. */
    suspend fun setAlbumArt(albumId: Long, source: Uri): String? = withContext(Dispatchers.IO) {
        val stored = copyIn(source, "album-$albumId").getOrElse { return@withContext it.describe() }
        replace(stored, _overrides.value.albumArt[albumId]) {
            it.copy(albumArt = it.albumArt + (albumId to stored))
        }
    }

    /** @return null on success, or a human-readable reason the image could not be saved. */
    suspend fun setArtistArt(artistId: Long, source: Uri): String? = withContext(Dispatchers.IO) {
        val stored = copyIn(source, "artist-$artistId").getOrElse { return@withContext it.describe() }
        replace(stored, _overrides.value.artistArt[artistId]) {
            it.copy(artistArt = it.artistArt + (artistId to stored))
        }
    }

    private fun Throwable.describe() = this::class.simpleName + ": " + (message ?: "unknown error")

    suspend fun clearAlbumArt(albumId: Long) = withContext(Dispatchers.IO) {
        val old = _overrides.value.albumArt[albumId]
        update { it.copy(albumArt = it.albumArt - albumId) }?.let { error(it) }
        old?.let { File(it).delete() }
    }

    suspend fun clearArtistArt(artistId: Long) = withContext(Dispatchers.IO) {
        val old = _overrides.value.artistArt[artistId]
        update { it.copy(artistArt = it.artistArt - artistId) }?.let { error(it) }
        old?.let { File(it).delete() }
    }

    /**
     * Copies the picked image into app storage under a fresh filename. The name changes on every
     * write so Coil's disk/memory cache can't serve the previous image back.
     *
     * Failures are returned rather than swallowed: a silently ignored error here looks exactly
     * like "the button does nothing".
     */
    private fun copyIn(source: Uri, baseName: String): Result<String> = runCatching {
        val dir = artDir
        check(dir.isDirectory) { "Could not create ${dir.absolutePath}" }

        val target = File(dir, "$baseName-${System.currentTimeMillis()}.img")
        try {
            val stream = context.contentResolver.openInputStream(source)
                ?: error("Could not read the selected image")
            stream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_CUSTOM_ART_BYTES) { "The selected image is larger than 32 MiB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(target.length() > 0) { "The selected image was empty" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not an image" }
            target.absolutePath
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    /** @return null on success, or the reason the index could not be written. */
    private suspend fun update(transform: (ArtworkOverrides) -> ArtworkOverrides): String? =
        mutex.withLock {
            val next = transform(_overrides.value)
            runCatching {
                indexFile.writeText(
                    json.encodeToString(
                        OverridesFile(
                            albumArt = next.albumArt.mapKeys { it.key.toString() },
                            artistArt = next.artistArt.mapKeys { it.key.toString() },
                        )
                    )
                )
            }.onSuccess { _overrides.value = next }.exceptionOrNull()?.describe()
        }

    private suspend fun replace(
        newPath: String,
        oldPath: String?,
        transform: (ArtworkOverrides) -> ArtworkOverrides,
    ): String? {
        val failure = update(transform)
        if (failure == null) oldPath?.takeIf { it != newPath }?.let { File(it).delete() }
        else File(newPath).delete()
        return failure
    }

    private fun Map<String, String>.mapNotNullKeysToLong(): Map<Long, String> =
        mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }
            // Drop entries whose backing file was cleared by the OS or an app-data wipe.
            .filter { File(it.second).exists() }
            .toMap()

    private companion object {
        const val MAX_CUSTOM_ART_BYTES = 32L * 1024 * 1024
    }
}
