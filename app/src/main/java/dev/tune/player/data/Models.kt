package dev.tune.player.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import kotlinx.serialization.Serializable

/**
 * One audio file, as read from MediaStore. [path] is the real filesystem path, which is what
 * folder filtering and embedded-artwork extraction both work from.
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String?,
    val composer: String?,
    val genre: String?,
    val track: Int,
    val disc: Int,
    val year: Int,
    /**
     * Full release date packed as `yyyymmdd`, or 0 when the tags gave nothing better than [year].
     * See [ReleaseDateReader] — this exists because MediaStore only reports a year, which makes a
     * single-year library unsortable by date.
     */
    val releaseDate: Int = 0,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long,
    val path: String,
) {
    /** Absolute path of the directory holding this file, without a trailing separator. */
    val folderPath: String get() = path.substringBeforeLast('/', "")

    /**
     * The MediaStore content URI, derived from [id] rather than stored alongside it. One less
     * field to keep consistent, and it leaves [Song] constructible without Android present —
     * which is what lets the sorting and duplicate logic be unit tested on the JVM.
     */
    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    /**
     * What "release date" sorts on: the tagged date when there is one, otherwise the year padded
     * out to `yyyy0000`. Padding rather than comparing years separately is what lets a year-only
     * file sort ahead of every dated file in the same year, which is the honest answer — it could
     * be any day of it.
     */
    val releaseDateKey: Int
        get() = if (releaseDate > 0) releaseDate else PackedDate.ofYear(year)
}

data class Album(
    val id: Long,
    val name: String,
    val artist: String,
    val artistId: Long,
    val year: Int,
    val songs: List<Song>,
) {
    val durationMs: Long get() = songs.sumOf { it.durationMs }

    /** Newest track date on the album — derived rather than stored, so it can't drift from [songs]. */
    val releaseDateKey: Int get() = songs.maxOfOrNull { it.releaseDateKey } ?: 0
}

data class Artist(
    val id: Long,
    val name: String,
    val songs: List<Song>,
    /** Albums credited to this artist. */
    val albums: List<Album>,
    /** Albums they only appear on, surfaced separately when collaborators are split out. */
    val appearsOn: List<Album> = emptyList(),
)

data class Genre(
    val id: Long,
    val name: String,
    val songs: List<Song>,
)

data class Folder(
    val path: String,
    val songCount: Int,
) {
    val name: String get() = path.substringAfterLast('/', path)
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<Long> = emptyList(),
)

/** Everything the UI renders, rebuilt whenever the library is rescanned or folders change. */
data class Library(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val folders: List<Folder> = emptyList(),
) {
    val isEmpty: Boolean get() = songs.isEmpty()
}

/**
 * How a list of songs is ordered in the UI.
 *
 * Every comparator is defined ascending, with direction applied separately, so the arrow shown in
 * the sort menu always tells the truth. That is why these are named for what they sort by rather
 * than for a direction — "Play count", not "Most played".
 */
enum class SortOrder(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    ARTIST_YEAR("Artist, then release date"),
    YEAR("Release date"),
    DURATION("Duration"),
    DATE_ADDED("Date added"),
    MOST_PLAYED("Play count"),
    RECENTLY_PLAYED("Last played"),
}

/** Sort orders for the album, artist, genre, playlist and folder tabs. */
enum class GroupSortOrder(val label: String) {
    NAME("Name"),
    ARTIST("Artist"),
    ARTIST_YEAR("Artist, then release date"),
    YEAR("Release date"),
    TRACK_COUNT("Track count"),
    MOST_PLAYED("Play count"),
    RECENTLY_PLAYED("Last played"),
}
