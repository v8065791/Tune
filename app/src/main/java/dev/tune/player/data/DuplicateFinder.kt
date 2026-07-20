package dev.tune.player.data

/** Songs that look like the same recording, plus the normalised key that grouped them. */
data class DuplicateGroup(val title: String, val artist: String, val songs: List<Song>)

/**
 * Finds songs that appear more than once in the library.
 *
 * Matching is on normalised title and artist rather than on file contents: the common case is the
 * same track present in two folders, or once loose and once inside an album rip. Comparing bytes
 * would miss those entirely, since they are usually different encodes.
 *
 * This only reports. Nothing here deletes or modifies a file — the user decides what to do with
 * what it finds.
 */
fun findDuplicates(songs: List<Song>): List<DuplicateGroup> =
    songs
        .groupBy { normalise(it.title) to normalise(it.artist) }
        .values
        .filter { it.size > 1 && normalise(it.first().title).isNotBlank() }
        .map { group ->
            DuplicateGroup(
                title = group.first().title,
                artist = group.first().artist,
                // Longest first, so the likely-best copy is the one shown at the top.
                songs = group.sortedByDescending { it.durationMs },
            )
        }
        .sortedBy { it.title.lowercase() }

/**
 * Strips everything that varies between copies of the same track: case, punctuation, spacing, and
 * the parenthesised suffixes tagging tools like to add.
 */
private fun normalise(value: String): String {
    var text = value.lowercase().trim()
    for (suffix in NOISE) text = text.replace(suffix, " ")
    return text.filter { it.isLetterOrDigit() || it.isWhitespace() }
        .split(WHITESPACE)
        .filter { it.isNotBlank() }
        .joinToString(" ")
}

private val WHITESPACE = Regex("\\s+")

/** Parenthesised or bracketed qualifiers that don't make a track a different recording. */
private val NOISE = listOf(
    Regex("\\((?:remaster(?:ed)?|explicit|clean|album version|bonus track)[^)]*\\)"),
    Regex("\\[(?:remaster(?:ed)?|explicit|clean|album version|bonus track)[^\\]]*\\]"),
)
