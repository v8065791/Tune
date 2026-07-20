package dev.tune.player.data

import kotlin.math.pow

/** Which ReplayGain tag, if any, is applied to playback volume. */
enum class ReplayGainMode(val label: String) {
    OFF("Off"),
    TRACK("Track gain"),
    ALBUM("Album gain, then track"),
}

/**
 * Loudness tags read from a file.
 *
 * Gains are in dB at the ReplayGain reference level. Peaks are sample amplitudes where 1.0 is
 * full scale; they are what makes a positive gain safe to apply.
 */
data class ReplayGainInfo(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
) {
    val isEmpty: Boolean get() = trackGainDb == null && albumGainDb == null

    /**
     * The player volume this file should play at under [mode].
     *
     * A positive gain is only allowed as far as the peak says is safe: boosting past `1/peak`
     * would clip, and there is no limiter in the chain to catch it. With no peak tag the gain is
     * capped at unity, so an untagged-peak file is quiet rather than distorted.
     */
    fun volumeFor(mode: ReplayGainMode): Float {
        if (mode == ReplayGainMode.OFF) return 1f
        val album = mode == ReplayGainMode.ALBUM
        val gain = if (album) albumGainDb ?: trackGainDb else trackGainDb ?: albumGainDb
        val peak = if (album) albumPeak ?: trackPeak else trackPeak ?: albumPeak
        if (gain == null) return 1f

        val ceiling = peak?.takeIf { it > 0f }?.let { 1f / it } ?: 1f
        return 10f.pow(gain / 20f).coerceAtMost(ceiling).coerceIn(MIN_VOLUME, MAX_VOLUME)
    }

    private companion object {
        const val MIN_VOLUME = 0.05f
        const val MAX_VOLUME = 1f
    }
}

/**
 * Picks the loudness tags out of whatever [TagReader] finds in a file.
 *
 * Opus additionally uses `R128_*` tags at a different reference level, and an output gain baked
 * into its header; both are converted so one number comes out the other side.
 */
object ReplayGainReader {

    fun read(path: String): ReplayGainInfo {
        val gains = GainBuilder()
        val headerGainDb = TagReader.read(path) { key, value -> gains.accept(key, value) }
        return gains.build(headerGainDb)
    }

    /**
     * Accumulates whichever loudness keys a file happens to carry.
     *
     * Opus files tag with `R128_*`, measured against EBU R128's -23 LUFS. ReplayGain targets
     * -18 LUFS, so those values are shifted by [R128_TO_REPLAYGAIN_DB] to put every format on one
     * scale. A file carrying both is taken at its ReplayGain value, since that needs no conversion.
     */
    private class GainBuilder {
        private var trackGain: Float? = null
        private var albumGain: Float? = null
        private var r128Track: Float? = null
        private var r128Album: Float? = null
        private var trackPeak: Float? = null
        private var albumPeak: Float? = null

        fun accept(rawKey: String, value: String) {
            when (rawKey.uppercase()) {
                "REPLAYGAIN_TRACK_GAIN" -> trackGain = parseGainDb(value)
                "REPLAYGAIN_ALBUM_GAIN" -> albumGain = parseGainDb(value)
                "REPLAYGAIN_TRACK_PEAK" -> trackPeak = parsePeak(value)
                "REPLAYGAIN_ALBUM_PEAK" -> albumPeak = parsePeak(value)
                "R128_TRACK_GAIN" -> r128Track = parseR128(value)
                "R128_ALBUM_GAIN" -> r128Album = parseR128(value)
            }
        }

        /**
         * [headerGainDb] is Opus's own output gain, which the specification says always applies.
         * It is folded in here rather than applied separately, which means switching ReplayGain
         * off also skips it — a deliberate simplification, and near-universally zero in practice.
         */
        fun build(headerGainDb: Float = 0f): ReplayGainInfo {
            fun combine(gain: Float?): Float? =
                gain?.plus(headerGainDb) ?: headerGainDb.takeIf { it != 0f }

            return ReplayGainInfo(
                trackGainDb = combine(trackGain ?: r128Track),
                albumGainDb = combine(albumGain ?: r128Album),
                trackPeak = trackPeak,
                albumPeak = albumPeak,
            )
        }
    }

    /** Values look like "-7.50 dB"; take the leading signed number and ignore the rest. */
    private fun parseGainDb(value: String): Float? =
        GAIN_PATTERN.find(value.trim())?.value?.toFloatOrNull()?.takeIf { it in MIN_DB..MAX_DB }

    private fun parsePeak(value: String): Float? =
        value.trim().toFloatOrNull()?.takeIf { it > 0f && it <= MAX_PEAK }

    /** R128 gains are Q7.8 fixed point: a whole number of 1/256 dB steps. */
    private fun parseR128(value: String): Float? =
        value.trim().toIntOrNull()
            ?.let { it / 256f + R128_TO_REPLAYGAIN_DB }
            ?.takeIf { it in MIN_DB..MAX_DB }

    private val GAIN_PATTERN = Regex("^[+-]?\\d+(?:\\.\\d+)?")

    private const val MIN_DB = -60f
    private const val MAX_DB = 60f
    private const val MAX_PEAK = 8f

    /** R128 targets -23 LUFS, ReplayGain -18 LUFS; the difference puts both on one scale. */
    private const val R128_TO_REPLAYGAIN_DB = 5f
}
