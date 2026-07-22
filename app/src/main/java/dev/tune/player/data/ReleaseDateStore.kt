package dev.tune.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
private data class DateFile(
    val dates: Map<String, DateEntry> = emptyMap(),
    val songKeys: Map<String, DateEntry> = emptyMap(),
)

/** [modified] is the file's mtime when [date] was read — how a re-tagged file invalidates itself. */
@Serializable
private data class DateEntry(val date: Int, val modified: Long)

/**
 * Full release dates for songs whose tags carry one, cached on disk.
 *
 * MediaStore's `YEAR` column is an `Int`, so a library recorded across one year sorts as a single
 * tie. Getting the real date means opening each file, which is exactly the per-file work the
 * scanner otherwise avoids — hence the cache. A song is re-read only when its mtime changes.
 *
 * Nothing here writes to the audio files; they are opened read-only and only the header is read.
 */
class ReleaseDateStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicTextFile(context.filesDir.resolve("release-dates.json"))

    private var cache: MutableMap<String, DateEntry> = mutableMapOf()
    private var legacy: MutableMap<Long, DateEntry> = mutableMapOf()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!file.exists) return@withContext
        val stored = json.decodeFromString<DateFile>(file.readText())
        cache = stored.songKeys.toMutableMap()
        legacy = stored.dates.mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }
            .toMap().toMutableMap()
        Unit
    }

    /**
     * Returns [songs] with [Song.releaseDate] filled in wherever a tag supplied one.
     *
     * Songs whose format carries no readable date are skipped outright rather than opened and
     * found wanting, and cached results are reused, so a steady-state rescan reads no files at all.
     */
    suspend fun enrich(songs: List<Song>): List<Song> {
        val stale = songs.filter { song ->
            song.hasReadableDate &&
                (cache[song.stableKey] ?: legacy[song.id])?.modified != song.dateModifiedSeconds
        }

        if (stale.isNotEmpty()) {
            val gate = Semaphore(MAX_CONCURRENT_READS)
            val fresh = withContext(Dispatchers.IO) {
                coroutineScope {
                    stale.map { song ->
                        async {
                            gate.withPermit {
                                song.stableKey to DateEntry(
                                    date = ReleaseDateReader.read(song.path),
                                    modified = song.dateModifiedSeconds,
                                )
                            }
                        }
                    }.awaitAll()
                }
            }
            // Entries are kept even when the read found nothing, so a dateless file is opened
            // once rather than on every scan.
            cache.putAll(fresh)
            runCatching { persist() }
        }

        // Promote legacy id entries only after the current MediaStore rows are known.
        var migrated = false
        for (song in songs) {
            val old = legacy.remove(song.id) ?: continue
            cache.putIfAbsent(song.stableKey, old)
            migrated = true
        }

        // Songs that have gone away shouldn't keep growing the file.
        val live = songs.mapTo(HashSet()) { it.stableKey }
        if (cache.keys.retainAll(live) || migrated || legacy.isNotEmpty()) {
            legacy.clear()
            runCatching { persist() }
        }

        return songs.map { song ->
            val date = cache[song.stableKey]?.date ?: ReleaseDateReader.NONE
            if (date > 0) song.copy(releaseDate = date) else song
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        file.writeText(json.encodeToString(DateFile(songKeys = cache)))
        Unit
    }

    private companion object {
        /**
         * Enough to keep storage busy without spawning a thread per song on a large library. Each
         * read touches only the header, so these finish quickly.
         */
        const val MAX_CONCURRENT_READS = 8
    }
}

/**
 * Whether it is worth opening this file to look for a date.
 *
 * Extension rather than MIME type: MediaStore reports Opus as `audio/ogg` on some devices and
 * `audio/opus` on others, and the extension is what the parser actually keys off anyway.
 */
private val Song.hasReadableDate: Boolean
    get() = path.substringAfterLast('.', "").lowercase(Locale.ROOT) in DATE_BEARING_EXTENSIONS

private val DATE_BEARING_EXTENSIONS = setOf(
    "opus", "ogg", "oga", "flac", "mp3", "m4a", "mp4", "alac", "aac",
)
