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

    suspend fun setAlbumArt(albumId: Long, source: Uri) = withContext(Dispatchers.IO) {
        val stored = copyIn(source, "album-$albumId") ?: return@withContext
        update { it.copy(albumArt = it.albumArt + (albumId to stored)) }
    }

    suspend fun setArtistArt(artistId: Long, source: Uri) = withContext(Dispatchers.IO) {
        val stored = copyIn(source, "artist-$artistId") ?: return@withContext
        update { it.copy(artistArt = it.artistArt + (artistId to stored)) }
    }

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
     */
    private fun copyIn(source: Uri, baseName: String): String? = runCatching {
        artDir.listFiles { f -> f.name.startsWith("$baseName-") }?.forEach { it.delete() }
        val target = File(artDir, "$baseName-${System.currentTimeMillis()}.img")
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    }.getOrNull()

    private fun update(transform: (ArtworkOverrides) -> ArtworkOverrides) {
        val next = transform(_overrides.value)
        _overrides.value = next
        runCatching {
            indexFile.writeText(
                json.encodeToString(
                    OverridesFile(
                        albumArt = next.albumArt.mapKeys { it.key.toString() },
                        artistArt = next.artistArt.mapKeys { it.key.toString() },
                    )
                )
            )
        }
    }

    private fun Map<String, String>.mapNotNullKeysToLong(): Map<Long, String> =
        mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }
            // Drop entries whose backing file was cleared by the OS or an app-data wipe.
            .filter { File(it.second).exists() }
            .toMap()
}
