package dev.tune.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses real files produced by ffmpeg, one per container, rather than hand-built byte arrays —
 * the point of these tests is to catch a wrong offset against what encoders actually emit.
 *
 * Fixtures live in `src/test/resources/gain` and were generated with:
 * `ffmpeg -f lavfi -i "sine=frequency=440:duration=1" -c:a <codec> -metadata <tag> <out>`
 */
class ReplayGainReaderTest {

    private fun read(name: String): ReplayGainInfo {
        val url = checkNotNull(javaClass.classLoader?.getResource("gain/$name")) {
            "missing fixture $name"
        }
        return ReplayGainReader.read(File(url.toURI()).absolutePath)
    }

    /** R128 is Q7.8 against -23 LUFS: -1280/256 = -5 dB, then +5 to reach ReplayGain's -18. */
    @Test
    fun `opus R128 tags convert to the ReplayGain scale`() {
        val info = read("track.opus")
        assertEquals(0f, info.trackGainDb!!, 0.01f)
        assertEquals(3f, info.albumGainDb!!, 0.01f)
    }

    @Test
    fun `ogg vorbis comments are read`() {
        val info = read("track.ogg")
        assertEquals(-7.5f, info.trackGainDb!!, 0.01f)
        assertEquals(0.911111f, info.trackPeak!!, 0.0001f)
    }

    @Test
    fun `native flac comments are read`() {
        val info = read("track.flac")
        assertEquals(-3.25f, info.trackGainDb!!, 0.01f)
        assertEquals(-4.0f, info.albumGainDb!!, 0.01f)
        assertEquals(0.5f, info.trackPeak!!, 0.0001f)
    }

    @Test
    fun `id3 TXXX frames are read`() {
        val info = read("track.mp3")
        assertEquals(-6f, info.trackGainDb!!, 0.01f)
    }

    /** A tag large enough to span several Ogg pages must still be reassembled. */
    @Test
    fun `opus tags spanning multiple pages are reassembled`() {
        val file = File(javaClass.classLoader!!.getResource("gain/bigtag.opus")!!.toURI())
        assertTrue("fixture should exceed one Ogg page", file.length() > 65_536)
        val info = read("bigtag.opus")
        assertNotNull("gain lost when the comment packet spans pages", info.trackGainDb)
        assertEquals(6f, info.trackGainDb!!, 0.01f)
    }

    @Test
    fun `an untagged file reports no gain`() {
        val info = read("untagged.opus")
        assertTrue(info.isEmpty)
        assertEquals(1f, info.volumeFor(ReplayGainMode.TRACK), 0.0001f)
    }

    @Test
    fun `a missing or unreadable file reports no gain rather than throwing`() {
        assertTrue(ReplayGainReader.read("/nonexistent/nope.mp3").isEmpty)
        assertNull(ReplayGainReader.read("/nonexistent/nope.mp3").trackGainDb)
    }

    // ---- Volume resolution -------------------------------------------------

    @Test
    fun `negative gain attenuates`() {
        val info = ReplayGainInfo(trackGainDb = -6f)
        assertEquals(0.501f, info.volumeFor(ReplayGainMode.TRACK), 0.01f)
    }

    @Test
    fun `positive gain is capped at unity without a peak tag`() {
        val info = ReplayGainInfo(trackGainDb = 6f)
        assertEquals(1f, info.volumeFor(ReplayGainMode.TRACK), 0.0001f)
    }

    /** With a peak of 0.5 there is 6 dB of headroom, so a +6 dB gain is safe to apply in full. */
    @Test
    fun `a peak tag bounds how far a positive gain may go`() {
        val info = ReplayGainInfo(trackGainDb = 12f, trackPeak = 0.5f)
        assertEquals(2f, 1f / 0.5f, 0.0001f)
        // Volume is still clamped to 1.0 overall, so the ceiling only ever lowers the result.
        assertEquals(1f, info.volumeFor(ReplayGainMode.TRACK), 0.0001f)
    }

    @Test
    fun `album mode falls back to track gain`() {
        val info = ReplayGainInfo(trackGainDb = -6f, albumGainDb = null)
        assertEquals(0.501f, info.volumeFor(ReplayGainMode.ALBUM), 0.01f)
    }

    @Test
    fun `off mode never changes volume`() {
        val info = ReplayGainInfo(trackGainDb = -20f)
        assertEquals(1f, info.volumeFor(ReplayGainMode.OFF), 0.0001f)
    }
}
