package dev.tune.player.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Serialises the disk write against a concurrent clear. */
    private val mutex = Mutex()

    /** App-lifetime; this store is a singleton, so the scope never needs cancelling. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A "stats changed, please persist" signal. Conflated to a single slot so a burst of plays
     * collapses to one pending write rather than queueing one write each.
     */
    private val dirty =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        // The in-memory map is updated synchronously on every play, but the file — a full
        // O(library) rewrite — is decoupled: after each write the writer waits out a minimum
        // interval, and any plays during it coalesce into one pending flag. Steady-state writes
        // drop from one-per-play to at most one per interval. A play lost to a hard kill inside
        // that window is acceptable; these are non-critical stats, not the resume state.
        scope.launch {
            dirty.collect {
                persist()
                delay(PERSIST_INTERVAL_MS)
            }
        }
    }

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
        _stats.update { current ->
            val previous = current[songId] ?: PlayStat()
            current + (songId to PlayStat(previous.count + 1, atEpochMs))
        }
        dirty.tryEmit(Unit)
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _stats.value = emptyMap()
            runCatching { file.delete() }
        }
        Unit
    }

    private suspend fun persist() = mutex.withLock {
        val snapshot = _stats.value
        runCatching {
            file.writeText(json.encodeToString(StatsFile(snapshot.mapKeys { it.key.toString() })))
        }
        Unit
    }

    private companion object {
        /** Minimum gap between stats file writes; bursts of plays coalesce within it. */
        const val PERSIST_INTERVAL_MS = 10_000L
    }
}

/** Total plays across a set of songs — how album, artist and genre counts are derived. */
fun Map<Long, PlayStat>.totalPlays(songs: List<Song>): Int =
    songs.sumOf { this[it.id]?.count ?: 0 }

/** The most recent play across a set of songs, or 0 if none have been played. */
fun Map<Long, PlayStat>.lastPlayed(songs: List<Song>): Long =
    songs.maxOfOrNull { this[it.id]?.lastPlayedEpochMs ?: 0L } ?: 0L
