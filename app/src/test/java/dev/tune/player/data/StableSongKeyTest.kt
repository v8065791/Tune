package dev.tune.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StableSongKeyTest {

    @Test
    fun `storage volume does not change identity`() {
        assertEquals(
            stableSongKey("/storage/emulated/0/Music/Album/Track.flac", 1234, 90_500),
            stableSongKey("/storage/ABCD-1234/Music/Album/Track.flac", 1234, 90_500),
        )
    }

    @Test
    fun `different files at the same path do not share identity`() {
        assertNotEquals(
            stableSongKey("/storage/emulated/0/Music/track.flac", 1234, 90_000),
            stableSongKey("/storage/emulated/0/Music/track.flac", 5678, 90_000),
        )
    }
}
