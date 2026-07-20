package dev.tune.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFinderTest {

    private var nextId = 1L

    private fun song(
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        durationMs: Long = 180_000,
        year: Int = 2020,
        track: Int = 1,
        disc: Int = 1,
        dateAdded: Long = 0,
        path: String = "/music/$title.mp3",
    ) = Song(
        id = nextId++,
        title = title,
        artist = artist,
        artistId = artist.hashCode().toLong(),
        album = album,
        albumId = album.hashCode().toLong(),
        albumArtist = artist,
        composer = null,
        genre = null,
        track = track,
        disc = disc,
        year = year,
        durationMs = durationMs,
        sizeBytes = 1_000,
        mimeType = "audio/mpeg",
        dateAddedSeconds = dateAdded,
        dateModifiedSeconds = 0,
        path = path,
    )

    @Test
    fun `identical title and artist group together`() {
        val groups = findDuplicates(
            listOf(song("Song A"), song("Song A"), song("Song B")),
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.first().songs.size)
    }

    @Test
    fun `case punctuation and spacing do not stop a match`() {
        val groups = findDuplicates(listOf(song("Don't Stop"), song("dont   stop")))
        assertEquals(1, groups.size)
    }

    @Test
    fun `remaster style suffixes are ignored`() {
        val groups = findDuplicates(listOf(song("Song A"), song("Song A (Remastered 2011)")))
        assertEquals(1, groups.size)
    }

    @Test
    fun `a different artist is not a duplicate`() {
        val groups = findDuplicates(
            listOf(song("Song A", artist = "One"), song("Song A", artist = "Two")),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `different lengths still count as duplicates and the longest comes first`() {
        val groups = findDuplicates(
            listOf(song("Song A", durationMs = 100), song("Song A", durationMs = 200)),
        )
        assertEquals(1, groups.size)
        assertEquals(200L, groups.first().songs.first().durationMs)
    }

    @Test
    fun `blank titles are not reported as duplicates of each other`() {
        assertTrue(findDuplicates(listOf(song("  "), song(""))).isEmpty())
    }

    @Test
    fun `a library with no repeats reports nothing`() {
        assertTrue(findDuplicates(listOf(song("A"), song("B"), song("C"))).isEmpty())
    }

    // ---- Sorting -----------------------------------------------------------

    @Test
    fun `title order is natural, so track 2 precedes track 10`() {
        val songs = listOf(song("Track 10"), song("Track 2"))
        val sorted = songs.sortedWith(SortOrder.TITLE.comparator(NameCollator(false)))
        assertEquals("Track 2", sorted.first().title)
    }

    @Test
    fun `descending reverses the order`() {
        val songs = listOf(song("A"), song("B"))
        val sorted = songs.sortedWith(
            SortOrder.TITLE.comparator(NameCollator(false), descending = true),
        )
        assertEquals("B", sorted.first().title)
    }

    @Test
    fun `release date sorts oldest first when ascending`() {
        val songs = listOf(song("New", year = 2020), song("Old", year = 1999))
        val sorted = songs.sortedWith(SortOrder.YEAR.comparator(NameCollator(false)))
        assertEquals("Old", sorted.first().title)
    }

    @Test
    fun `artist then release date groups by artist before ordering by year`() {
        val songs = listOf(
            song("Late", artist = "B", year = 2020),
            song("Early", artist = "B", year = 2000),
            song("Only", artist = "A", year = 2010),
        )
        val sorted = songs.sortedWith(SortOrder.ARTIST_YEAR.comparator(NameCollator(false)))
        assertEquals(listOf("Only", "Early", "Late"), sorted.map { it.title })
    }

    @Test
    fun `leading articles are ignored when the setting is on`() {
        val songs = listOf(song("The Beatles"), song("Abba"))
        val sorted = songs.sortedWith(SortOrder.TITLE.comparator(NameCollator(true)))
        assertEquals("Abba", sorted.first().title)
    }

    @Test
    fun `album grouping sorts by artist then release date`() {
        val albums = listOf(
            Album(2, "Later", "Zed", 1, 2020, emptyList()),
            Album(3, "Earlier", "Zed", 1, 2001, emptyList()),
            Album(1, "Solo", "Ann", 2, 2010, emptyList()),
        )
        val sorted = albums.sortedWith(
            GroupSortOrder.ARTIST_YEAR.albumComparator(NameCollator(false), emptyMap()),
        )
        assertEquals(listOf("Solo", "Earlier", "Later"), sorted.map { it.name })
    }

    @Test
    fun `play count order is ascending so the direction toggle stays honest`() {
        val quiet = song("Quiet")
        val loud = song("Loud")
        val stats = mapOf(quiet.id to PlayStat(1), loud.id to PlayStat(99))
        val sorted = listOf(loud, quiet).sortedWith(
            SortOrder.MOST_PLAYED.comparator(NameCollator(false), stats),
        )
        assertEquals("Quiet", sorted.first().title)
    }
}
