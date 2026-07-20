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
private data class StatsFile(val plays: Map<String, PlayStat> = emptyMap())

@Serializable
data class PlayStat(val count: Int = 0, val lastPlayedEpochMs: Long = 0L)

/**
 * Play counts, keyed by song id.
 *
 * Only songs are counted. Album, artist and genre figures are derived by summing their tracks, so
 * there is one number to keep consistent rather than four that can drift apart.
 */
class PlayStatsStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(context.filesDir, "play-stats.json")

    private val _stats = MutableStateFlow<Map<Long, PlayStat>>(emptyMap())
    val stats: StateFlow<Map<Long, PlayStat>> = _stats.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _stats.value = runCatching {
            if (!file.exists()) return@runCatching emptyMap()
            json.decodeFromString<StatsFile>(file.readText())
                .plays
                .mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }
                .toMap()
        }.getOrElse { emptyMap() }
    }

    /** Records one play of [songId]. Called when a track actually starts, not when queued. */
    suspend fun recordPlay(songId: Long, atEpochMs: Long) = withContext(Dispatchers.IO) {
        val current = _stats.value[songId] ?: PlayStat()
        val next = _stats.value + (songId to PlayStat(current.count + 1, atEpochMs))
        _stats.value = next
        runCatching {
            file.writeText(
                json.encodeToString(StatsFile(next.mapKeys { it.key.toString() }))
            )
        }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        _stats.value = emptyMap()
        runCatching { file.delete() }
        Unit
    }
}

/** Total plays across a set of songs — how album, artist and genre counts are derived. */
fun Map<Long, PlayStat>.totalPlays(songs: List<Song>): Int =
    songs.sumOf { this[it.id]?.count ?: 0 }

/** The most recent play across a set of songs, or 0 if none have been played. */
fun Map<Long, PlayStat>.lastPlayed(songs: List<Song>): Long =
    songs.maxOfOrNull { this[it.id]?.lastPlayedEpochMs ?: 0L } ?: 0L
