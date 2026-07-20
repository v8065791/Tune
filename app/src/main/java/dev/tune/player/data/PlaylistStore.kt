package dev.tune.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** User playlists, persisted as a single JSON file in app storage. */
class PlaylistStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(context.filesDir, "playlists.json")

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _playlists.value = runCatching {
            if (!file.exists()) return@runCatching emptyList()
            json.decodeFromString<List<Playlist>>(file.readText())
        }.getOrElse { emptyList() }
    }

    suspend fun create(name: String, songIds: List<Long> = emptyList()): Playlist {
        val playlist = Playlist(id = UUID.randomUUID().toString(), name = name, songIds = songIds)
        mutate { it + playlist }
        return playlist
    }

    suspend fun rename(id: String, name: String) =
        mutate { list -> list.map { if (it.id == id) it.copy(name = name) else it } }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    /** Adds songs, skipping any already present so the playlist can't accumulate duplicates. */
    suspend fun addSongs(id: String, songIds: List<Long>) = mutate { list ->
        list.map { playlist ->
            if (playlist.id != id) playlist
            else playlist.copy(songIds = playlist.songIds + songIds.filterNot { it in playlist.songIds })
        }
    }

    suspend fun removeSong(id: String, songId: Long) = mutate { list ->
        list.map { if (it.id == id) it.copy(songIds = it.songIds - songId) else it }
    }

    private suspend fun mutate(transform: (List<Playlist>) -> List<Playlist>) =
        withContext(Dispatchers.IO) {
            val next = transform(_playlists.value)
            _playlists.value = next
            runCatching { file.writeText(json.encodeToString(next)) }
            Unit
        }
}
