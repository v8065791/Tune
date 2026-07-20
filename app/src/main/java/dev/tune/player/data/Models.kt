package dev.tune.player.data

import android.net.Uri
import kotlinx.serialization.Serializable

/**
 * One audio file, as read from MediaStore. [path] is the real filesystem path, which is what
 * folder filtering and embedded-artwork extraction both work from.
 */
data class Song(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val albumId: Long,
    val albumArtist: String?,
    val composer: String?,
    val genre: String?,
    val track: Int,
    val disc: Int,
    val year: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long,
    val path: String,
) {
    /** Absolute path of the directory holding this file, without a trailing separator. */
    val folderPath: String get() = path.substringBeforeLast('/', "")
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

/** How a list of songs is ordered in the UI. */
enum class SortOrder(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    YEAR("Year"),
    DURATION("Duration"),
    DATE_ADDED("Date added"),
    MOST_PLAYED("Most played"),
    RECENTLY_PLAYED("Recently played"),
}

/** Sort orders for the album, artist and genre grids. */
enum class GroupSortOrder(val label: String) {
    NAME("Name"),
    YEAR("Year"),
    TRACK_COUNT("Track count"),
    MOST_PLAYED("Most played"),
    RECENTLY_PLAYED("Recently played"),
}
