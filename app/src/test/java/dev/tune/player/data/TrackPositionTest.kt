package dev.tune.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Devices disagree on how MediaStore reports an absent `DISC_NUMBER` — null on some, 0 on others.
 * Rather than discover which, the decoder is made correct under every reported shape and pinned
 * here, so the answer stops mattering.
 */
class TrackPositionTest {

    @Test
    fun `a plain track number on a single-disc release`() {
        assertEquals(TrackPosition(track = 5, disc = 0), decodeTrackAndDisc(5, null))
    }

    @Test
    fun `DTTT packing is unpacked when the disc column says nothing`() {
        assertEquals(TrackPosition(track = 5, disc = 2), decodeTrackAndDisc(2005, null))
    }

    /** The bug: a device reporting 0 rather than null used to swallow the disc number. */
    @Test
    fun `DTTT packing is unpacked when the disc column reports zero`() {
        assertEquals(TrackPosition(track = 5, disc = 2), decodeTrackAndDisc(2005, 0))
    }

    @Test
    fun `an explicit disc number wins over the packed one`() {
        assertEquals(TrackPosition(track = 5, disc = 3), decodeTrackAndDisc(2005, 3))
    }

    @Test
    fun `an explicit disc number applies to an unpacked track`() {
        assertEquals(TrackPosition(track = 5, disc = 2), decodeTrackAndDisc(5, 2))
    }

    @Test
    fun `disc two track zero is not mistaken for a track number`() {
        assertEquals(TrackPosition(track = 0, disc = 2), decodeTrackAndDisc(2000, null))
    }

    /** Exactly 1000 is disc 1 track 0, which the previous strict `>` decoded as track 1000. */
    @Test
    fun `disc one track zero is unpacked rather than read as track 1000`() {
        assertEquals(TrackPosition(track = 0, disc = 1), decodeTrackAndDisc(1000, null))
    }

    @Test
    fun `an untagged file has neither`() {
        assertEquals(TrackPosition(track = 0, disc = 0), decodeTrackAndDisc(0, null))
        assertEquals(TrackPosition(track = 0, disc = 0), decodeTrackAndDisc(0, 0))
    }

    @Test
    fun `a high disc number still unpacks`() {
        assertEquals(TrackPosition(track = 12, disc = 11), decodeTrackAndDisc(11012, null))
    }
}
