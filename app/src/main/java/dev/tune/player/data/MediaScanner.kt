package dev.tune.player.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the device's audio library out of MediaStore.
 *
 * MediaStore already holds the tags we care about (title/artist/album/year/track/genre), so a
 * single cursor pass gives us the whole library cheaply. Per-file parsing with
 * MediaMetadataRetriever is reserved for artwork, which MediaStore doesn't expose as bytes.
 */
object MediaScanner {

    private val PROJECTION: Array<String> = buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.TITLE)
        add(MediaStore.Audio.Media.ARTIST)
        add(MediaStore.Audio.Media.ALBUM)
        add(MediaStore.Audio.Media.ALBUM_ID)
        add(MediaStore.Audio.Media.COMPOSER)
        add(MediaStore.Audio.Media.TRACK)
        add(MediaStore.Audio.Media.YEAR)
        add(MediaStore.Audio.Media.DURATION)
        add(MediaStore.Audio.Media.SIZE)
        add(MediaStore.Audio.Media.MIME_TYPE)
        add(MediaStore.Audio.Media.DATE_ADDED)
        add(MediaStore.Audio.Media.DATE_MODIFIED)
        @Suppress("DEPRECATION")
        add(MediaStore.Audio.Media.DATA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(MediaStore.Audio.Media.ALBUM_ARTIST)
            add(MediaStore.Audio.Media.GENRE)
            add(MediaStore.Audio.Media.DISC_NUMBER)
        }
    }.toTypedArray()

    /** Reads every music file MediaStore knows about. Excludes ringtones, alarms and podcasts. */
    suspend fun scan(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val songs = ArrayList<Song>()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            PROJECTION,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val reader = ColumnReader(cursor)
            while (cursor.moveToNext()) {
                songs += reader.readSong() ?: continue
            }
        }
        songs
    }

    /**
     * Column indices resolved once instead of per row — on a large library that is the difference
     * between a snappy scan and a visibly slow one.
     */
    private class ColumnReader(private val cursor: Cursor) {
        private val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        private val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        private val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        private val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        private val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        private val composer = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPOSER)
        private val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        private val year = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
        private val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        private val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        private val mime = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
        private val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        private val dateModified = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

        @Suppress("DEPRECATION")
        private val data = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

        // Only present on API 30+; -1 means "column not queried".
        private val albumArtist = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
        private val genre = cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
        private val disc = cursor.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER)

        fun readSong(): Song? {
            val path = cursor.getStringOrNull(data) ?: return null
            val songId = cursor.getLong(id)
            val position = decodeTrackAndDisc(
                rawTrack = cursor.getIntOrNull(track) ?: 0,
                rawDisc = cursor.getIntOrNull(disc),
            )

            return Song(
                id = songId,
                title = cursor.getStringOrNull(title) ?: path.substringAfterLast('/'),
                artist = cursor.getStringOrNull(artist)?.takeUnless { it == UNKNOWN } ?: "Unknown artist",
                album = cursor.getStringOrNull(album)?.takeUnless { it == UNKNOWN } ?: "Unknown album",
                albumId = cursor.getLong(albumId),
                albumArtist = cursor.getStringOrNull(albumArtist),
                composer = cursor.getStringOrNull(composer),
                genre = cursor.getStringOrNull(genre),
                track = position.track,
                disc = position.disc,
                year = cursor.getIntOrNull(year) ?: 0,
                durationMs = cursor.getLongOrNull(duration) ?: 0L,
                sizeBytes = cursor.getLongOrNull(size) ?: 0L,
                mimeType = cursor.getStringOrNull(mime) ?: "audio/*",
                dateAddedSeconds = cursor.getLongOrNull(dateAdded) ?: 0L,
                dateModifiedSeconds = cursor.getLongOrNull(dateModified) ?: 0L,
                path = path,
            )
        }

        private fun Cursor.getStringOrNull(index: Int): String? =
            if (index < 0 || isNull(index)) null else getString(index)?.takeIf { it.isNotBlank() }

        private fun Cursor.getIntOrNull(index: Int): Int? =
            if (index < 0 || isNull(index)) null else getInt(index)

        private fun Cursor.getLongOrNull(index: Int): Long? =
            if (index < 0 || isNull(index)) null else getLong(index)
    }

    private const val UNKNOWN = "<unknown>"
}

/** Where a song sits on its release. Zero means "the tags didn't say", not "the first one". */
data class TrackPosition(val track: Int, val disc: Int)

/**
 * Decodes MediaStore's track and disc columns.
 *
 * `TRACK` packs multi-disc releases as DTTT — disc 2 track 5 arrives as 2005. `DISC_NUMBER` is the
 * newer, explicit column, but it only exists on API 30+ and devices disagree on how they report an
 * absent value: some give null, others 0. Both are treated as "not told", so the DTTT fallback
 * still runs instead of a missing disc silently becoming disc 0.
 *
 * That disagreement is the reason this is a free function rather than inline in the cursor loop —
 * it is the one piece of scanning that can be tested without a device.
 */
internal fun decodeTrackAndDisc(rawTrack: Int, rawDisc: Int?): TrackPosition {
    // >= rather than >: a bare 1000 is disc 1 track 0, which the old > let through as track 1000.
    val packed = rawTrack >= 1000
    return TrackPosition(
        track = if (packed) rawTrack % 1000 else rawTrack,
        disc = rawDisc?.takeIf { it > 0 } ?: if (packed) rawTrack / 1000 else 0,
    )
}
