package dev.tune.player.data

/**
 * Reads a full release date out of a file's tags.
 *
 * MediaStore exposes only `YEAR`, an `Int`. That is fine for a shelf of albums but useless for a
 * library where everything shares a year — every song ties, and the sort silently degrades to its
 * tiebreaker. Tags routinely carry the full date (`DATE=2026-04-16`), so it is read directly.
 *
 * Dates come back packed as `yyyymmdd` ([PackedDate]) rather than as a timestamp: it sorts
 * correctly as a plain `Int`, needs no timezone, and represents "2026" and "2026-04" honestly as
 * `20260000` and `20260400` instead of inventing a January 1st that the tag never claimed.
 *
 * Only formats carrying Vorbis comments (Opus, Ogg Vorbis, Ogg FLAC, native FLAC) and ID3v2 text
 * frames are covered. Anything else returns [NONE] and the caller falls back to MediaStore's year.
 */
object ReleaseDateReader {

    const val NONE = 0

    /**
     * Keys in preference order.
     *
     * `DATE` wins over `ORIGINALDATE` because it describes *this* release, which is what the sort
     * is labelled as. The plain-year keys are last: they can only ever produce what MediaStore
     * already gave us, so they are a fallback, not a source.
     */
    private val KEYS = listOf(
        "DATE",
        "RELEASEDATE",
        "ORIGINALDATE",
        "TDRC",
        "TDRL",
        "TDOR",
        "YEAR",
        "ORIGINALYEAR",
        "TYER",
        "TORY",
    )

    fun read(path: String): Int {
        val found = HashMap<String, Int>()
        TagReader.read(path) { key, value ->
            val upper = key.uppercase()
            // Only the first value of a repeated key is kept — later duplicates are usually a
            // second tagger's opinion, and the first is what other players show.
            if (upper in KEYS && upper !in found) {
                parse(value)?.let { found[upper] = it }
            }
        }
        return KEYS.firstNotNullOfOrNull { found[it] } ?: NONE
    }

    /**
     * Accepts what taggers actually emit: `2026-04-16`, `2026-04`, `2026`, `20260416`, and
     * ISO timestamps like `2026-04-16T12:00:00Z`. Anything else yields null rather than a guess.
     */
    fun parse(raw: String): Int? {
        val text = raw.trim()
        if (text.length < 4) return null

        val year = text.take(4).toIntOrNull()?.takeIf { it in MIN_YEAR..MAX_YEAR } ?: return null

        // Either "yyyy-mm-dd" style with separators, or a bare "yyyymmdd".
        val rest = text.drop(4)
        val digits = if (rest.isNotEmpty() && !rest[0].isDigit()) {
            rest.drop(1).takeWhile { it.isDigit() || it == '-' || it == '/' || it == '.' }
                .split('-', '/', '.')
        } else {
            rest.takeWhile { it.isDigit() }.chunked(2)
        }

        val month = digits.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..12 } ?: 0
        val day = if (month == 0) 0 else digits.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..31 } ?: 0

        return PackedDate.of(year, month, day)
    }

    private const val MIN_YEAR = 1000
    private const val MAX_YEAR = 9999
}

/** Helpers for the packed `yyyymmdd` form. Zero month or day means "the tag didn't say". */
object PackedDate {

    fun of(year: Int, month: Int, day: Int): Int = year * 10_000 + month * 100 + day

    /** A year on its own, as MediaStore reports it. */
    fun ofYear(year: Int): Int = if (year > 0) year * 10_000 else ReleaseDateReader.NONE

    fun year(packed: Int): Int = packed / 10_000

    fun month(packed: Int): Int = (packed / 100) % 100

    fun day(packed: Int): Int = packed % 100

    /** For the metadata sheet: "2026-04-16", "2026-04" or "2026". */
    fun format(packed: Int): String? {
        if (packed <= 0) return null
        val year = year(packed)
        val month = month(packed)
        val day = day(packed)
        return when {
            month == 0 -> "$year"
            day == 0 -> "%04d-%02d".format(year, month)
            else -> "%04d-%02d-%02d".format(year, month, day)
        }
    }
}
