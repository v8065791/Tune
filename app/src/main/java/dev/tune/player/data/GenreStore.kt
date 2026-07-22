package dev.tune.player.data

import android.content.Context
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

@Serializable
private data class GenreFile(
    val genres: Map<String, String> = emptyMap(),
    val songKeys: Map<String, String> = emptyMap(),
)

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
    private val file = AtomicTextFile(context.filesDir.resolve("genre-overrides.json"))
    private var portable: Map<String, String> = emptyMap()

    private val _overrides = MutableStateFlow<Map<Long, String>>(emptyMap())
    val overrides: StateFlow<Map<Long, String>> = _overrides.asStateFlow()

    /** Serialises read-modify-write-persist so concurrent assigns can't lose each other. */
    private val mutex = Mutex()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!file.exists) return@withContext
        val stored = json.decodeFromString<GenreFile>(file.readText())
        _overrides.value = stored.genres.mapNotNull { (id, genre) ->
            id.toLongOrNull()?.let { it to genre }
        }.toMap()
        portable = stored.songKeys
    }

    /** Assigns [genre] to every song in [songIds]; a blank genre removes the assignment. */
    suspend fun assign(songs: Collection<Song>, genre: String) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        val trimmed = genre.trim()
        mutex.withLock {
            val nextIds = _overrides.value.toMutableMap().apply {
                if (trimmed.isEmpty()) songs.forEach { remove(it.id) }
                else songs.forEach { put(it.id, trimmed) }
            }
            val nextKeys = portable.toMutableMap().apply {
                if (trimmed.isEmpty()) songs.forEach { remove(it.stableKey) }
                else songs.forEach { put(it.stableKey, trimmed) }
            }
            persist(nextIds, nextKeys)
            _overrides.value = nextIds
            portable = nextKeys
        }
    }

    /** Resolves portable keys and upgrades legacy MediaStore-id entries after each scan. */
    suspend fun reconcile(songs: List<Song>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val byId = songs.associateBy { it.id }
            val byKey = songs.associateBy { it.stableKey }
            val resolved = mutableMapOf<Long, String>()
            val keys = portable.toMutableMap()
            for ((key, genre) in portable) byKey[key]?.let { resolved[it.id] = genre }
            for ((id, genre) in _overrides.value) {
                byId[id]?.let {
                    resolved[it.id] = genre
                    keys.putIfAbsent(it.stableKey, genre)
                }
            }
            if (resolved != _overrides.value || keys != portable) {
                persist(resolved, keys)
                _overrides.value = resolved
                portable = keys
            }
        }
    }

    suspend fun replacePortable(values: Map<String, String>, songs: List<Song>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val byKey = songs.associateBy { it.stableKey }
                val ids = values.mapNotNull { (key, genre) -> byKey[key]?.let { it.id to genre } }.toMap()
                persist(ids, values)
                _overrides.value = ids
                portable = values
            }
        }

    private fun persist(ids: Map<Long, String>, keys: Map<String, String>) {
        file.writeText(
            json.encodeToString(
                GenreFile(
                    genres = ids.mapKeys { it.key.toString() },
                    songKeys = keys,
                )
            )
        )
    }
}
