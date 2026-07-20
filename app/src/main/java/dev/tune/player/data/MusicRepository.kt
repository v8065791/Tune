package dev.tune.player.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single source of truth for the music library.
 *
 * Raw scan results are held separately from the filtered [library] so that toggling a folder
 * re-groups in memory instead of re-reading MediaStore.
 */
@OptIn(FlowPreview::class)
class MusicRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val preferences = UserPreferences(context)
    val artwork = ArtworkStore(context)
    val playlists = PlaylistStore(context)
    val playStats = PlayStatsStore(context)
    val genres = GenreStore(context)

    private val allSongs = MutableStateFlow<List<Song>>(emptyList())

    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Every folder on disk, including excluded ones — the folder picker needs the full set. */
    private val _allFolders = MutableStateFlow<List<Folder>>(emptyList())
    val allFolders: StateFlow<List<Folder>> = _allFolders.asStateFlow()

    /** The library-shaping settings, bundled so [combine] stays within its arity limits. */
    private data class LibraryOptions(
        val folders: Set<String>,
        val folderMode: FolderMode,
        val sort: SortOrder,
        val sortDescending: Boolean,
        val separators: String,
        val autoSortNames: Boolean,
        val hideCollaborators: Boolean,
        val stats: Map<Long, PlayStat> = emptyMap(),
    )

    init {
        val folderOptions =
            combine(preferences.excludedFolders, preferences.folderMode, ::Pair)

        // Order and direction travel together, keeping combine within its five-flow arity.
        val sortOptions = combine(preferences.songSort, preferences.sortDescending, ::Pair)

        val options =
            combine(
                folderOptions,
                sortOptions,
                preferences.separators,
                preferences.autoSortNames,
                preferences.hideCollaborators,
            ) { (folders, mode), (sort, descending), separators, autoSort, hideCollabs ->
                LibraryOptions(
                    folders, mode, sort, descending, separators, autoSort, hideCollabs,
                )
            }

        scope.launch {
            // Play counts feed the "most played" orders, so the library re-sorts as they change.
            combine(
                allSongs,
                options,
                playStats.stats,
                genres.overrides,
            ) { songs, opts, stats, genreOverrides ->
                buildLibrary(songs, opts.copy(stats = stats), genreOverrides)
            }.collect { _library.value = it }
        }

        // Rescan when the system reports the media library changed, if the user wants it.
        scope.launch {
            preferences.observing.collectLatest { enabled ->
                if (enabled) observeMediaStore()
            }
        }
    }

    /**
     * Watches MediaStore and refreshes on change. Changes arrive in bursts while another app
     * writes files, so wait for a quiet period before doing the (expensive) rescan.
     */
    private suspend fun observeMediaStore() {
        val changes = MutableStateFlow(0L)
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    changes.value = changes.value + 1
                }
            }

        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants = */ true,
            observer,
        )
        try {
            changes.debounce(MEDIA_CHANGE_DEBOUNCE_MS).drop(1).collect { refresh() }
        } finally {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    suspend fun initialise() {
        artwork.load()
        playlists.load()
        playStats.load()
        genres.load()
        refresh()
    }

    /** Re-reads MediaStore. Safe to call repeatedly; the UI shows [isScanning] while it runs. */
    suspend fun refresh() {
        _isScanning.value = true
        try {
            val songs = MediaScanner.scan(context)
            allSongs.value = songs
            _allFolders.value = songs.groupingBy { it.folderPath }
                .eachCount()
                .map { (path, count) -> Folder(path, count) }
                .sortedBy { it.path }
        } finally {
            _isScanning.value = false
        }
    }

    fun songById(id: Long): Song? = _library.value.songs.firstOrNull { it.id == id }

    fun songsByIds(ids: List<Long>): List<Song> {
        val byId = _library.value.songs.associateBy { it.id }
        // Preserve the caller's ordering — playlists are order-sensitive.
        return ids.mapNotNull { byId[it] }
    }

    fun albumById(id: Long): Album? = _library.value.albums.firstOrNull { it.id == id }

    fun artistById(id: Long): Artist? = _library.value.artists.firstOrNull { it.id == id }

    fun songsInFolder(path: String): List<Song> =
        _library.value.songs.filter { it.folderPath == path }

    /** A representative song per album, used to pull embedded artwork. */
    fun artSongForAlbum(albumId: Long): Song? = albumById(albumId)?.songs?.firstOrNull()

    fun artSongForArtist(artistId: Long): Song? = artistById(artistId)?.songs?.firstOrNull()

    suspend fun toggleFolder(path: String, excluded: Boolean) {
        preferences.setFolderExcluded(path, excluded)
    }

    suspend fun currentExcludedFolders(): Set<String> = preferences.excludedFolders.first()

    private suspend fun buildLibrary(
        scanned: List<Song>,
        options: LibraryOptions,
        genreOverrides: Map<Long, String>,
    ): Library =
        withContext(Dispatchers.Default) {
        // User-assigned genres are layered on before anything is grouped, so the override shows
        // up everywhere a genre does — the Genres tab, the metadata sheet, search.
        val songs =
            if (genreOverrides.isEmpty()) scanned
            else scanned.map { song -> genreOverrides[song.id]?.let { song.copy(genre = it) } ?: song }
        val sort = options.sort
        val collator = NameCollator(options.autoSortNames)
        // Selecting a directory always covers its subfolders, so picking one top-level folder
        // is enough — matching how a directory picker is normally understood.
        fun Song.isUnder(dir: String) = folderPath == dir || folderPath.startsWith("$dir/")

        val visible = when {
            options.folders.isEmpty() -> songs
            options.folderMode == FolderMode.INCLUDE ->
                songs.filter { song -> options.folders.any(song::isUnder) }
            else -> songs.filterNot { song -> options.folders.any(song::isUnder) }
        }

        val albums = visible.groupBy { it.albumId }
            .map { (albumId, albumSongs) ->
                val first = albumSongs.first()
                val artistName = first.albumArtistName()
                Album(
                    id = albumId,
                    name = first.album,
                    artist = artistName,
                    artistId = artistIdOf(artistName),
                    year = albumSongs.maxOf { it.year },
                    songs = albumSongs.sortedWith(compareBy({ it.disc }, { it.track }, { it.title })),
                )
            }
            .sortedWith(collator.comparingBy { it.name })

        val albumsByArtist = albums.groupBy { it.artistId }

        // Group by album artist, not track artist: otherwise a compilation credited to forty
        // featured performers becomes forty separate artists. Songs missing an ALBUM_ARTIST tag
        // fall back to their track artist. Separators split "A; B" into distinct credits.
        val artistCredits = mutableMapOf<String, MutableSet<Song>>()
        for (song in visible) {
            for (name in song.albumArtistName().splitTags(options.separators)) {
                artistCredits.getOrPut(name) { linkedSetOf() }.add(song)
            }
        }

        val artists = artistCredits
            .map { (artistName, artistSongs) ->
                val artistId = artistIdOf(artistName)
                val own = albumsByArtist[artistId].orEmpty()
                // An album counts as an appearance when the artist is on it but isn't its
                // credited album artist — a guest verse shouldn't file the whole record here.
                val appearsOn =
                    if (!options.hideCollaborators) emptyList()
                    else artistSongs.map { it.albumId }.distinct()
                        .mapNotNull { id -> albums.firstOrNull { it.id == id } }
                        .filterNot { it.artistId == artistId }

                Artist(
                    id = artistId,
                    name = artistName,
                    songs = artistSongs.sortedWith(collator.comparingBy { it.title }),
                    albums = own.sortedByDescending { it.year },
                    appearsOn = appearsOn.sortedByDescending { it.year },
                )
            }
            .sortedWith(collator.comparingBy { it.name })

        val genres = visible
            .flatMap { song -> song.genre.orEmpty().splitTags(options.separators).map { it to song } }
            .groupBy({ it.first }, { it.second })
            .map { (name, genreSongs) ->
                Genre(
                    id = artistIdOf(name),
                    name = name,
                    songs = genreSongs.sortedWith(collator.comparingBy { it.title }),
                )
            }
            .sortedWith(collator.comparingBy { it.name })

        val folders = visible.groupingBy { it.folderPath }
            .eachCount()
            .map { (path, count) -> Folder(path, count) }
            .sortedBy { it.path }

        Library(
            songs = visible.sortedWith(
                sort.comparator(collator, options.stats, options.sortDescending)
            ),
            albums = albums,
            artists = artists,
            genres = genres,
            folders = folders,
        )
    }

    private companion object {
        const val MEDIA_CHANGE_DEBOUNCE_MS = 2_000L
    }
}

/**
 * Splits a multi-value tag on any of [separators].
 *
 * Splitting is opt-in and off by default: plenty of legitimate names contain "/" or "&", so
 * guessing would fragment libraries that are correctly tagged.
 */
private fun String.splitTags(separators: String): List<String> {
    if (separators.isEmpty()) return listOf(this).filter { it.isNotBlank() }
    return split(*separators.toCharArray())
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { listOf(this) }
}

/**
 * Orders names the way a person would read them.
 *
 * Comparison is natural rather than lexical, so "Track 2" sorts before "Track 10" instead of
 * after it. Optionally ignores a leading article, filing "The Beatles" under B.
 */
class NameCollator(private val ignoreArticles: Boolean) {

    fun key(name: String): String {
        val lower = name.lowercase()
        if (!ignoreArticles) return lower
        for (article in ARTICLES) {
            if (lower.startsWith(article)) return lower.removePrefix(article)
        }
        return lower
    }

    fun <T> comparingBy(select: (T) -> String): Comparator<T> =
        Comparator { a, b -> compareNatural(key(select(a)), key(select(b))) }

    /** Walks both strings together, comparing runs of digits as numbers and the rest as text. */
    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]

            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                // Compare as numbers, so leading zeros don't change the order.
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    private companion object {
        val ARTICLES = listOf("the ", "a ", "an ")
    }
}

/** The name a song should be filed under in the Artists tab. */
private fun Song.albumArtistName(): String = albumArtist?.takeIf { it.isNotBlank() } ?: artist

/**
 * A stable identity for an album artist.
 *
 * MediaStore has no ALBUM_ARTIST_ID to borrow, so identity is derived from the name itself. It
 * must stay stable across rescans, since custom artist images are stored against it — hence a
 * plain hash of the normalized name rather than an index or row id.
 */
private fun artistIdOf(name: String): Long = name.lowercase().hashCode().toLong()

/**
 * All comparators are ascending; [descending] reverses whichever is chosen. Keeping direction out
 * of the orders themselves means the sort menu's arrow matches what the list actually does.
 */
fun SortOrder.comparator(
    collator: NameCollator = NameCollator(false),
    stats: Map<Long, PlayStat> = emptyMap(),
    descending: Boolean = false,
): Comparator<Song> {
    val ascending: Comparator<Song> = when (this) {
        SortOrder.TITLE -> collator.comparingBy { it.title }
        SortOrder.ARTIST ->
            collator.comparingBy<Song> { it.artist }
                .thenBy { it.album.lowercase() }
                .thenBy { it.track }
        SortOrder.ALBUM -> compareBy({ it.album.lowercase() }, { it.disc }, { it.track })
        SortOrder.ARTIST_YEAR ->
            collator.comparingBy<Song> { it.artist }
                .thenBy { it.year }
                .thenBy { it.album.lowercase() }
                .thenBy { it.disc }
                .thenBy { it.track }
        SortOrder.YEAR -> compareBy<Song> { it.year }.thenBy { it.title.lowercase() }
        SortOrder.DURATION -> compareBy { it.durationMs }
        SortOrder.DATE_ADDED -> compareBy { it.dateAddedSeconds }
        SortOrder.MOST_PLAYED -> compareBy { stats[it.id]?.count ?: 0 }
        SortOrder.RECENTLY_PLAYED -> compareBy { stats[it.id]?.lastPlayedEpochMs ?: 0L }
    }
    return if (descending) ascending.reversed() else ascending
}
