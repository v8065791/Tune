package dev.tune.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** User playlists, persisted as a single JSON file in app storage. */
class PlaylistStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = AtomicTextFile(context.filesDir.resolve("playlists.json"))

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    /** Serialises read-modify-write-persist so concurrent edits can't lose each other's changes. */
    private val mutex = Mutex()

    suspend fun load() = withContext(Dispatchers.IO) {
        _playlists.value = if (file.exists) {
            json.decodeFromString<List<Playlist>>(file.readText())
        } else emptyList()
    }

    suspend fun create(
        name: String,
        songIds: List<Long> = emptyList(),
        songKeys: List<String> = emptyList(),
    ): Playlist {
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name,
            songIds = songIds,
            songKeys = songKeys,
        )
        mutate { it + playlist }
        return playlist
    }

    suspend fun rename(id: String, name: String) =
        mutate { list -> list.map { if (it.id == id) it.copy(name = name) else it } }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    /** Adds songs, skipping any already present so the playlist can't accumulate duplicates. */
    suspend fun addSongs(id: String, songIds: List<Long>, songKeys: List<String>) = mutate { list ->
        list.map { playlist ->
            if (playlist.id != id) playlist
            else {
                val existingKeys = playlist.songKeys.toHashSet()
                val additions = songIds.zip(songKeys).filterNot { it.second in existingKeys }
                playlist.copy(
                    songIds = playlist.songIds + additions.map { it.first },
                    songKeys = playlist.songKeys + additions.map { it.second },
                )
            }
        }
    }

    suspend fun removeSong(id: String, songId: Long, songKey: String?) = mutate { list ->
        list.map { playlist ->
            if (playlist.id != id) playlist
            else playlist.copy(
                songIds = playlist.songIds - songId,
                songKeys = songKey?.let { playlist.songKeys - it } ?: playlist.songKeys,
            )
        }
    }

    /** Resolves portable keys to this device's MediaStore ids and upgrades legacy id-only files. */
    suspend fun reconcile(songs: List<Song>) = mutateIfChanged { playlists ->
        val byId = songs.associateBy { it.id }
        val byKey = songs.associateBy { it.stableKey }
        playlists.map { playlist ->
            val resolved = if (playlist.songKeys.isNotEmpty()) {
                playlist.songKeys.mapNotNull(byKey::get)
            } else {
                playlist.songIds.mapNotNull(byId::get)
            }
            playlist.copy(
                songIds = resolved.map { it.id },
                songKeys = if (playlist.songKeys.isNotEmpty()) playlist.songKeys
                    else resolved.map { it.stableKey },
            )
        }
    }

    suspend fun replaceAll(playlists: List<Playlist>) = mutate { playlists }

    private suspend fun mutate(transform: (List<Playlist>) -> List<Playlist>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = transform(_playlists.value)
                file.writeText(json.encodeToString(next))
                _playlists.value = next
            }
            Unit
        }

    private suspend fun mutateIfChanged(transform: (List<Playlist>) -> List<Playlist>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = _playlists.value
                val next = transform(current)
                if (next != current) {
                    file.writeText(json.encodeToString(next))
                    _playlists.value = next
                }
            }
        }
}
