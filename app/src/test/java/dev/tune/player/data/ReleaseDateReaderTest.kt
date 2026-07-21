package dev.tune.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The bug this guards against: MediaStore reports only a year, so a library recorded inside one
 * year tied on every comparison and the sort silently fell through to its title tiebreaker.
 */
class ReleaseDateReaderTest {

    private fun fixture(name: String): String =
        File("src/test/resources/gain/$name").absolutePath

    // ---- Parsing -----------------------------------------------------------

    @Test
    fun `parses a full ISO date`() {
        assertEquals(20260416, ReleaseDateReader.parse("2026-04-16"))
    }

    @Test
    fun `parses a year and month`() {
        assertEquals(20260400, ReleaseDateReader.parse("2026-04"))
    }

    @Test
    fun `parses a bare year`() {
        assertEquals(20260000, ReleaseDateReader.parse("2026"))
    }

    @Test
    fun `parses a compact date without separators`() {
        assertEquals(20260416, ReleaseDateReader.parse("20260416"))
    }

    @Test
    fun `parses an ISO timestamp by ignoring the time`() {
        assertEquals(20260416, ReleaseDateReader.parse("2026-04-16T12:34:56Z"))
    }

    @Test
    fun `tolerates slashes and dots`() {
        assertEquals(20260416, ReleaseDateReader.parse("2026/04/16"))
        assertEquals(20260416, ReleaseDateReader.parse("2026.04.16"))
    }

    @Test
    fun `rejects nonsense rather than guessing`() {
        assertNull(ReleaseDateReader.parse(""))
        assertNull(ReleaseDateReader.parse("unknown"))
        assertNull(ReleaseDateReader.parse("26"))
    }

    @Test
    fun `drops an out-of-range month or day instead of storing it`() {
        assertEquals(20260000, ReleaseDateReader.parse("2026-13-01"))
        assertEquals(20260400, ReleaseDateReader.parse("2026-04-99"))
    }

    // ---- Ordering ----------------------------------------------------------

    @Test
    fun `packed dates sort chronologically`() {
        val dates = listOf("2026-07-19", "2026-04-16", "2025-12-31", "2026-04-19")
            .mapNotNull(ReleaseDateReader::parse)
            .sorted()
        assertEquals(listOf(20251231, 20260416, 20260419, 20260719), dates)
    }

    @Test
    fun `a year-only file sorts ahead of dated files in the same year`() {
        val yearOnly = PackedDate.ofYear(2026)
        val dated = ReleaseDateReader.parse("2026-01-01")!!
        assert(yearOnly < dated) { "$yearOnly should precede $dated" }
    }

    /** The actual reported bug, reproduced against the comparator. */
    @Test
    fun `songs sharing a year sort by date rather than by title`() {
        fun song(id: Long, title: String, date: Int) = SONG.copy(
            id = id,
            title = title,
            year = 2026,
            releaseDate = date,
        )

        val songs = listOf(
            song(1, "Absolution", 20260524),
            song(2, "Betrayal", 20260416),
            song(3, "Threshold", 20260705),
        )

        val sorted = songs.sortedWith(SortOrder.YEAR.comparator())
        assertEquals(listOf("Betrayal", "Absolution", "Threshold"), sorted.map { it.title })
    }

    @Test
    fun `songs with no tagged date still order by year`() {
        val older = SONG.copy(id = 1, title = "Zulu", year = 1999)
        val newer = SONG.copy(id = 2, title = "Alpha", year = 2026)
        val sorted = listOf(newer, older).sortedWith(SortOrder.YEAR.comparator())
        assertEquals(listOf("Zulu", "Alpha"), sorted.map { it.title })
    }

    // ---- Real files --------------------------------------------------------

    @Test
    fun `reads a date from a real opus file`() {
        assertEquals(20260416, ReleaseDateReader.read(fixture("dated.opus")))
    }

    @Test
    fun `reads a date from a real ogg vorbis file`() {
        assertEquals(20260416, ReleaseDateReader.read(fixture("dated.ogg")))
    }

    @Test
    fun `reads a date from a real flac file`() {
        assertEquals(20260416, ReleaseDateReader.read(fixture("dated.flac")))
    }

    @Test
    fun `reads a date from a real mp3 file`() {
        // ID3v2.4 writes TDRC; the year-only fallback would give 20260000, so this also proves
        // the full date survived.
        assertEquals(20260416, ReleaseDateReader.read(fixture("dated.mp3")))
    }

    @Test
    fun `an untagged file yields no date`() {
        assertEquals(ReleaseDateReader.NONE, ReleaseDateReader.read(fixture("untagged.opus")))
    }

    @Test
    fun `a missing file yields no date rather than throwing`() {
        assertEquals(ReleaseDateReader.NONE, ReleaseDateReader.read("/does/not/exist.opus"))
    }

    /**
     * Cover art sits in the tag block as a large base64 comment. If the reader gave up on big
     * blocks, or ran out of Ogg packets, the date would vanish on exactly the files people have.
     */
    @Test
    fun `finds the date in a file carrying embedded cover art`() {
        assertEquals(20260416, ReleaseDateReader.read(fixture("bigtag.opus")))
    }

    // ---- Formatting --------------------------------------------------------

    @Test
    fun `formats dates for display at whatever precision the tag gave`() {
        assertEquals("2026-04-16", PackedDate.format(20260416))
        assertEquals("2026-04", PackedDate.format(20260400))
        assertEquals("2026", PackedDate.format(20260000))
        assertNull(PackedDate.format(0))
    }

    private companion object {
        val SONG = Song(
            id = 0,
            title = "",
            artist = "Artist",
            album = "Album",
            albumId = 1,
            albumArtist = null,
            composer = null,
            genre = null,
            track = 0,
            disc = 0,
            year = 0,
            durationMs = 1000,
            sizeBytes = 1000,
            mimeType = "audio/opus",
            dateAddedSeconds = 0,
            dateModifiedSeconds = 0,
            path = "/music/song.opus",
        )
    }
}
