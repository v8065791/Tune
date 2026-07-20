package dev.tune.player.data

import android.content.Context
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
private data class GenreFile(val genres: Map<String, String> = emptyMap())

/**
 * User-assigned genres, keyed by song id.
 *
 * These are stored beside the app's own data and layered over whatever the file's tags say when
 * the library is built — **the audio files themselves are never written to**. That is the same
 * rule custom artwork follows, and it means assigning a genre can't corrupt a file, but also that
 * assignments live and die with the app's data and won't follow the files elsewhere.
 *
 * A blank assignment clears the override rather than storing an empty genre, so a song always
 * falls back to its own tag.
 */
class GenreStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(context.filesDir, "genre-overrides.json")

    private val _overrides = MutableStateFlow<Map<Long, String>>(emptyMap())
    val overrides: StateFlow<Map<Long, String>> = _overrides.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _overrides.value = runCatching {
            if (!file.exists()) return@runCatching emptyMap()
            json.decodeFromString<GenreFile>(file.readText())
                .genres
                .mapNotNull { (id, genre) -> id.toLongOrNull()?.let { it to genre } }
                .toMap()
        }.getOrElse { emptyMap() }
    }

    /** Assigns [genre] to every song in [songIds]; a blank genre removes the assignment. */
    suspend fun assign(songIds: Collection<Long>, genre: String) = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext
        val trimmed = genre.trim()
        _overrides.value = _overrides.value.toMutableMap().apply {
            if (trimmed.isEmpty()) songIds.forEach(::remove)
            else songIds.forEach { put(it, trimmed) }
        }
        persist()
    }

    private fun persist() {
        runCatching {
            file.writeText(
                json.encodeToString(GenreFile(_overrides.value.mapKeys { it.key.toString() }))
            )
        }
    }
}
