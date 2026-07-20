package dev.tune.player.data

/**
 * Comparators for the tabs that list groups rather than songs.
 *
 * Play figures are derived from the group's tracks, matching how [PlayStatsStore] defines them —
 * an album's play count is the sum of its songs', not a separate number that can drift.
 *
 * As with [SortOrder], every comparator here is ascending and `descending` reverses it, so the
 * arrow in the sort menu is always accurate.
 *
 * Not every order is meaningful for every group: an artist has no single release date, a folder
 * has no artist. Those fall back to name rather than producing an arbitrary order, so the list
 * never looks randomly shuffled.
 */
private fun <T> Comparator<T>.directed(descending: Boolean): Comparator<T> =
    if (descending) reversed() else this

fun GroupSortOrder.albumComparator(
    collator: NameCollator,
    stats: Map<Long, PlayStat>,
    descending: Boolean = false,
): Comparator<Album> {
    val ascending: Comparator<Album> = when (this) {
        GroupSortOrder.NAME -> collator.comparingBy { it.name }
        GroupSortOrder.ARTIST ->
            collator.comparingBy<Album> { it.artist }.thenBy { it.name.lowercase() }
        GroupSortOrder.ARTIST_YEAR ->
            collator.comparingBy<Album> { it.artist }
                .thenBy { it.releaseDateKey }
                .thenBy { it.name.lowercase() }
        GroupSortOrder.YEAR -> compareBy<Album> { it.releaseDateKey }.thenBy { it.name.lowercase() }
        GroupSortOrder.TRACK_COUNT -> compareBy { it.songs.size }
        GroupSortOrder.MOST_PLAYED -> compareBy { stats.totalPlays(it.songs) }
        GroupSortOrder.RECENTLY_PLAYED -> compareBy { stats.lastPlayed(it.songs) }
    }
    return ascending.directed(descending)
}

fun GroupSortOrder.artistComparator(
    collator: NameCollator,
    stats: Map<Long, PlayStat>,
    descending: Boolean = false,
): Comparator<Artist> {
    val ascending: Comparator<Artist> = when (this) {
        // An artist spans many releases, so the date-based orders have nothing to sort on.
        GroupSortOrder.NAME,
        GroupSortOrder.ARTIST,
        GroupSortOrder.ARTIST_YEAR,
        GroupSortOrder.YEAR -> collator.comparingBy { it.name }
        GroupSortOrder.TRACK_COUNT -> compareBy { it.songs.size }
        GroupSortOrder.MOST_PLAYED -> compareBy { stats.totalPlays(it.songs) }
        GroupSortOrder.RECENTLY_PLAYED -> compareBy { stats.lastPlayed(it.songs) }
    }
    return ascending.directed(descending)
}

fun GroupSortOrder.genreComparator(
    collator: NameCollator,
    stats: Map<Long, PlayStat>,
    descending: Boolean = false,
): Comparator<Genre> {
    val ascending: Comparator<Genre> = when (this) {
        GroupSortOrder.TRACK_COUNT -> compareBy { it.songs.size }
        GroupSortOrder.MOST_PLAYED -> compareBy { stats.totalPlays(it.songs) }
        GroupSortOrder.RECENTLY_PLAYED -> compareBy { stats.lastPlayed(it.songs) }
        else -> collator.comparingBy { it.name }
    }
    return ascending.directed(descending)
}

fun GroupSortOrder.folderComparator(
    collator: NameCollator,
    descending: Boolean = false,
): Comparator<Folder> {
    val ascending: Comparator<Folder> = when (this) {
        GroupSortOrder.TRACK_COUNT -> compareBy { it.songCount }
        // Folders carry no artist, no date and no aggregate play history of their own.
        else -> collator.comparingBy { it.name }
    }
    return ascending.directed(descending)
}

/**
 * Playlists hold song ids rather than songs, so play-based orders need the library to resolve
 * them. [songsOf] does that lookup.
 */
fun GroupSortOrder.playlistComparator(
    collator: NameCollator,
    stats: Map<Long, PlayStat>,
    descending: Boolean = false,
    songsOf: (Playlist) -> List<Song>,
): Comparator<Playlist> {
    val ascending: Comparator<Playlist> = when (this) {
        GroupSortOrder.TRACK_COUNT -> compareBy { it.songIds.size }
        GroupSortOrder.MOST_PLAYED -> compareBy { stats.totalPlays(songsOf(it)) }
        GroupSortOrder.RECENTLY_PLAYED -> compareBy { stats.lastPlayed(songsOf(it)) }
        else -> collator.comparingBy { it.name }
    }
    return ascending.directed(descending)
}
