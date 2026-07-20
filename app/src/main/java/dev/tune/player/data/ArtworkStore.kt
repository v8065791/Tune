package dev.tune.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val indexFile: File get() = File(context.filesDir, "artwork-overrides.json")
    private val artDir: File get() = File(context.filesDir, "artwork").apply { mkdirs() }

    private val _overrides = MutableStateFlow(ArtworkOverrides())
    val overrides: StateFlow<ArtworkOverrides> = _overrides.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val parsed = runCatching {
            if (!indexFile.exists()) return@runCatching OverridesFile()
            json.decodeFromString<OverridesFile>(indexFile.readText())
        }.getOrElse { OverridesFile() }

        _overrides.value = ArtworkOverrides(
            albumArt = parsed.albumArt.mapNotNullKeysToLong(),
            artistArt = parsed.artistArt.mapNotNullKeysToLong(),
        )
    }

    /** @return null on success, or a human-readable reason the image could not be saved. */
    suspend fun setAlbumArt(albumId: Long, source: Uri): String? = withContext(Dispatchers.IO) {
        val stored = copyIn(source, "album-$albumId").getOrElse { return@withContext it.describe() }
        update { it.copy(albumArt = it.albumArt + (albumId to stored)) }
    }

    /** @return null on success, or a human-readable reason the image could not be saved. */
    suspend fun setArtistArt(artistId: Long, source: Uri): String? = withContext(Dispatchers.IO) {
        val stored = copyIn(source, "artist-$artistId").getOrElse { return@withContext it.describe() }
        update { it.copy(artistArt = it.artistArt + (artistId to stored)) }
    }

    private fun Throwable.describe() = this::class.simpleName + ": " + (message ?: "unknown error")

    suspend fun clearAlbumArt(albumId: Long) = withContext(Dispatchers.IO) {
        _overrides.value.albumArt[albumId]?.let { File(it).delete() }
        update { it.copy(albumArt = it.albumArt - albumId) }
    }

    suspend fun clearArtistArt(artistId: Long) = withContext(Dispatchers.IO) {
        _overrides.value.artistArt[artistId]?.let { File(it).delete() }
        update { it.copy(artistArt = it.artistArt - artistId) }
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
        dir.listFiles { f -> f.name.startsWith("$baseName-") }?.forEach { it.delete() }

        val target = File(dir, "$baseName-${System.currentTimeMillis()}.img")
        val stream = context.contentResolver.openInputStream(source)
            ?: error("Could not read the selected image")
        stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }

        check(target.length() > 0) { "The selected image was empty" }
        target.absolutePath
    }

    /** @return null on success, or the reason the index could not be written. */
    private fun update(transform: (ArtworkOverrides) -> ArtworkOverrides): String? {
        val next = transform(_overrides.value)
        _overrides.value = next
        return runCatching {
            indexFile.writeText(
                json.encodeToString(
                    OverridesFile(
                        albumArt = next.albumArt.mapKeys { it.key.toString() },
                        artistArt = next.artistArt.mapKeys { it.key.toString() },
                    )
                )
            )
        }.exceptionOrNull()?.describe()
    }

    private fun Map<String, String>.mapNotNullKeysToLong(): Map<Long, String> =
        mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }
            // Drop entries whose backing file was cleared by the OS or an app-data wipe.
            .filter { File(it.second).exists() }
            .toMap()
}
