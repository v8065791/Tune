package dev.tune.player.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.tune.player.TuneApplication
import dev.tune.player.art.ArtRequest
import dev.tune.player.data.AccentColour
import dev.tune.player.data.Album
import dev.tune.player.data.Artist
import dev.tune.player.data.CoverMode
import dev.tune.player.data.DuplicateGroup
import dev.tune.player.data.Folder
import dev.tune.player.data.FolderMode
import dev.tune.player.data.Genre
import dev.tune.player.data.GroupSortOrder
import dev.tune.player.data.HomeTab
import dev.tune.player.data.NameCollator
import dev.tune.player.data.PlayInMode
import dev.tune.player.data.PlayStat
import dev.tune.player.data.ReplayGainInfo
import dev.tune.player.data.ReplayGainMode
import dev.tune.player.data.ReplayGainReader
import dev.tune.player.data.albumComparator
import dev.tune.player.data.artistComparator
import dev.tune.player.data.findDuplicates
import dev.tune.player.data.folderComparator
import dev.tune.player.data.genreComparator
import dev.tune.player.data.playlistComparator
import dev.tune.player.data.totalPlays
import dev.tune.player.data.Playlist
import dev.tune.player.data.Song
import dev.tune.player.data.SortOrder
import dev.tune.player.data.ThemeMode
import dev.tune.player.data.UserPreferences
import androidx.media3.common.Player
import dev.tune.player.player.PlayerController
import dev.tune.player.player.PlaybackProgress
import dev.tune.player.player.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Grouped matches for the search screen. */
data class SearchResults(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val genres: List<Genre> = emptyList(),
) {
    val isEmpty: Boolean
        get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && genres.isEmpty()
}

private data class SearchEntry<T>(val value: T, val text: String)

private data class SearchIndex(
    val songs: List<SearchEntry<Song>> = emptyList(),
    val albums: List<SearchEntry<Album>> = emptyList(),
    val artists: List<SearchEntry<Artist>> = emptyList(),
    val genres: List<SearchEntry<Genre>> = emptyList(),
)

@OptIn(FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as TuneApplication).repository

    val player = PlayerController(application, viewModelScope)

    val library = repository.library
    val isScanning = repository.isScanning
    val allFolders = repository.allFolders
    val playlists = repository.playlists.playlists
    val artworkOverrides = repository.artwork.overrides
    val playerState = player.state
    val playbackProgress = player.progress
    val sleepRemainingMs = player.sleepRemainingMs

    val excludedFolders: StateFlow<Set<String>> = repository.preferences.excludedFolders
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val songSort: StateFlow<SortOrder> = repository.preferences.songSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, SortOrder.TITLE)

    val groupSort: StateFlow<GroupSortOrder> = repository.preferences.groupSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, GroupSortOrder.NAME)

    val sortDescending: StateFlow<Boolean> = repository.preferences.sortDescending
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val groupSortDescending: StateFlow<Boolean> = repository.preferences.groupSortDescending
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Null means the detail screens keep each list's natural order. */
    val detailSort: StateFlow<SortOrder?> = repository.preferences.detailSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val detailSortDescending: StateFlow<Boolean> = repository.preferences.detailSortDescending
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- Settings ----------------------------------------------------------

    val preferences = repository.preferences

    private fun <T> Flow<T>.asState(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val themeMode = preferences.themeMode.asState(ThemeMode.SYSTEM)
    val blackTheme = preferences.blackTheme.asState(false)
    val dynamicColor = preferences.dynamicColor.asState(true)
    val coverMode = preferences.coverMode.asState(CoverMode.EMBEDDED)
    val squareCovers = preferences.squareCovers.asState(true)
    val roundedCorners = preferences.roundedCorners.asState(true)
    val homeTabs = preferences.homeTabs.asState(HomeTab.entries.toList())
    val accent = preferences.accent.asState(AccentColour.PURPLE)
    val gridView = preferences.gridView.asState(true)

    val observing = preferences.observing.asState(true)
    val autoSortNames = preferences.autoSortNames.asState(true)
    val hideCollaborators = preferences.hideCollaborators.asState(false)
    val separators = preferences.separators.asState("")
    val playInListWith = preferences.playInListWith.asState(PlayInMode.FROM_LIST)
    val rewindOnPrevious = preferences.rewindOnPrevious.asState(true)
    val rememberPlayback = preferences.rememberPlayback.asState(true)
    val keepShuffle = preferences.keepShuffle.asState(true)
    val replayGain = preferences.replayGain.asState(ReplayGainMode.OFF)

    /** Runs a preference write off the UI thread; every settings toggle funnels through here. */
    fun edit(block: suspend UserPreferences.() -> Unit) =
        viewModelScope.launch { preferences.block() }

    // ---- Selection ---------------------------------------------------------

    /** Ids of songs picked in selection mode. Empty means selection mode is off. */
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    val inSelectionMode: StateFlow<Boolean> =
        _selection.map { it.isNotEmpty() }.asState(false)

    fun toggleSelection(song: Song) {
        val current = _selection.value
        _selection.value =
            if (song.id in current) current - song.id else current + song.id
    }

    fun selectAll(songs: List<Song>) {
        _selection.value = songs.map { it.id }.toSet()
    }

    fun clearSelection() { _selection.value = emptySet() }

    /** Resolves the selection back to songs, in the order they appear in [from]. */
    private fun selectedSongs(from: List<Song>): List<Song> {
        val ids = _selection.value
        return from.filter { it.id in ids }
    }

    fun playSelection(from: List<Song>) {
        val songs = selectedSongs(from)
        if (songs.isNotEmpty()) player.play(songs)
        clearSelection()
    }

    fun queueSelection(from: List<Song>) {
        val songs = selectedSongs(from)
        if (songs.isNotEmpty()) player.addToQueue(songs)
        clearSelection()
    }

    fun addSelectionToPlaylist(playlistId: String, from: List<Song>) {
        val songs = selectedSongs(from)
        if (songs.isNotEmpty()) addToPlaylist(playlistId, songs.map { it.id })
        clearSelection()
    }

    // ---- Search ------------------------------------------------------------

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    /**
     * Case-insensitive substring match across songs, albums and artists. Recomputed from the
     * library so results follow rescans and folder changes.
     *
     * The filter runs on [Dispatchers.Default], not the collector's main thread — on a large
     * library it is a full linear scan per keystroke, and the group-sort flows below use the same
     * `flowOn`. The query is debounced so mid-typing keystrokes don't each trigger a scan.
     */
    private val searchIndex: StateFlow<SearchIndex> = library.map { lib ->
        SearchIndex(
            songs = lib.songs.map { SearchEntry(it, "${it.title}\u0000${it.artist}\u0000${it.album}".lowercase()) },
            albums = lib.albums.map { SearchEntry(it, "${it.name}\u0000${it.artist}".lowercase()) },
            artists = lib.artists.map { SearchEntry(it, it.name.lowercase()) },
            genres = lib.genres.map { SearchEntry(it, it.name.lowercase()) },
        )
    }.flowOn(Dispatchers.Default).asState(SearchIndex())

    val searchResults: StateFlow<SearchResults> =
        combine(searchIndex, _searchQuery.debounce(SEARCH_DEBOUNCE_MS)) { index, query ->
            val term = query.trim().lowercase()
            if (term.isBlank()) SearchResults()
            else SearchResults(
                songs = index.songs.filter { term in it.text }.map { it.value },
                albums = index.albums.filter { term in it.text }.map { it.value },
                artists = index.artists.filter { term in it.text }.map { it.value },
                genres = index.genres.filter { term in it.text }.map { it.value },
            )
        }.flowOn(Dispatchers.Default).asState(SearchResults())

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    /** Song whose full metadata sheet is open, if any. */
    private val _metadataSong = MutableStateFlow<Song?>(null)
    val metadataSong: StateFlow<Song?> = _metadataSong.asStateFlow()

    /** Transient user-facing messages, e.g. why an artwork change failed. */
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    init {
        player.connect()
        // Mirror playback prefs onto the controller as they change.
        viewModelScope.launch {
            preferences.rewindOnPrevious.collect { player.rewindOnPrevious = it }
        }
        viewModelScope.launch {
            repository.playStats.errors.collect { _messages.value = it }
        }


        // A play is counted once the track has actually been listened to for a while. Counting on
        // track change instead would let skipping through a queue inflate every count on the way
        // past. Position running backwards means the track restarted — on repeat, or a seek back
        // to the beginning — so the same song can legitimately be counted again.
        viewModelScope.launch {
            var lastId: Long? = null
            var lastPosition = 0L
            var counted = false
            combine(playerState, playbackProgress) { state, progress -> state to progress }
                .collect { (state, progress) ->
                    val id = state.currentSongId ?: return@collect
                    if (id != lastId || progress.positionMs < lastPosition) {
                        lastId = id
                        counted = false
                    }
                    lastPosition = progress.positionMs

                    // Short tracks would never reach a fixed threshold, so cap it at half their length.
                    val threshold =
                        if (progress.durationMs > 0) minOf(PLAY_COUNT_AFTER_MS, progress.durationMs / 2)
                        else PLAY_COUNT_AFTER_MS
                    if (!counted && progress.positionMs >= threshold) {
                        counted = true
                        repository.songById(id)?.let {
                            repository.playStats.recordPlay(it, System.currentTimeMillis())
                        }
                    }
                }
        }

        // Apply ReplayGain when the track changes. Reading the tag is deferred to here rather than
        // done during the library scan: it needs file I/O per track, and only the one about to
        // play matters.
        viewModelScope.launch {
            combine(
                playerState.map { it.currentSongId }.distinctUntilChanged(),
                preferences.replayGain,
            ) { id, mode -> id to mode }.collect { (id, mode) ->
                val song = id?.let(repository::songById)
                if (mode == ReplayGainMode.OFF || song == null) {
                    player.setVolume(1f)
                    return@collect
                }
                player.setVolume(replayGainFor(song).volumeFor(mode))
            }
        }

        // Persist the queue so it survives the process being killed. The position poll fires
        // roughly twice a second, so saving on every emission would rewrite DataStore
        // continuously. Instead the structure — queue, index, shuffle, repeat — is written only
        // when it actually changes, and the position on a coarse cadence while it advances.
        // `sample` stops emitting once playback stops changing the state, so a paused player
        // isn't written on a timer.
        viewModelScope.launch {
            playerState
                .distinctUntilChanged { a, b ->
                    a.queueIds == b.queueIds && a.queueIndex == b.queueIndex &&
                        a.shuffle == b.shuffle && a.repeatMode == b.repeatMode
                }
                .collect { persistResume(it, playbackProgress.value.positionMs) }
        }
        viewModelScope.launch {
            playbackProgress.sample(RESUME_POSITION_SAVE_MS).collect {
                persistResume(playerState.value, it.positionMs)
            }
        }
    }

    // ReplayGain is read from the playing track's file. Caching by path — invalidated by mtime,
    // the same signal ReleaseDateStore uses — means a track on repeat, or one revisited later in
    // the session, is read from disk once. Only ever touched from the single ReplayGain collector,
    // so a plain map is safe.
    private data class GainEntry(val modified: Long, val info: ReplayGainInfo)
    private val gainCache = HashMap<String, GainEntry>()

    private suspend fun replayGainFor(song: Song): ReplayGainInfo {
        gainCache[song.path]?.takeIf { it.modified == song.dateModifiedSeconds }?.let { return it.info }
        val info = withContext(Dispatchers.IO) { ReplayGainReader.read(song.path) }
        gainCache[song.path] = GainEntry(song.dateModifiedSeconds, info)
        return info
    }

    /** Writes the resume snapshot, honouring [rememberPlayback] and skipping an empty queue. */
    private suspend fun persistResume(state: PlayerState, positionMs: Long) {
        if (!rememberPlayback.value || state.queueIds.isEmpty()) return
        val queueKeys = repository.songsByIds(state.queueIds).associateBy { it.id }
        preferences.saveResumeState(
            state.queueIds,
            state.queueIds.mapNotNull { queueKeys[it]?.stableKey },
            state.queueIndex,
            positionMs,
            state.shuffle,
            state.repeatMode,
        )
    }

    /**
     * Puts back the queue from the last session, paused. Waits for the library so ids can be
     * resolved, and for the media controller to finish connecting.
     */
    private fun restorePlayback() = viewModelScope.launch {
        if (!preferences.rememberPlayback.first()) return@launch
        val saved = preferences.resumeState.first() ?: return@launch
        val songs = library.first { it.songs.isNotEmpty() }.songs
        val byId = songs.associateBy { it.id }
        val byKey = songs.associateBy { it.stableKey }
        val queue = if (saved.queueKeys.isNotEmpty()) saved.queueKeys.mapNotNull(byKey::get)
            else saved.queue.mapNotNull(byId::get)
        if (queue.isEmpty()) return@launch

        // Shuffle and repeat come back only when the user asked to keep them; otherwise the
        // player starts in its default off state.
        val keep = preferences.keepShuffle.first()
        val shuffle = keep && saved.shuffle
        val repeatMode = if (keep) saved.repeatMode else Player.REPEAT_MODE_OFF

        // The controller connects asynchronously; retry briefly rather than racing it.
        repeat(RESTORE_ATTEMPTS) {
            if (playerState.value.queueSize > 0) return@launch
            player.restore(queue, saved.index, saved.positionMs, shuffle, repeatMode)
            delay(RESTORE_RETRY_MS)
        }
    }

    fun onPermissionGranted() {
        if (_permissionGranted.value) return
        _permissionGranted.value = true
        viewModelScope.launch {
            runCatching { repository.initialise() }
                .onSuccess { failures -> if (failures.isNotEmpty()) _messages.value = failures.joinToString("\n") }
                .onFailure { reportFailure("Library scan", it) }
        }
        restorePlayback()
    }

    fun refresh() = launchAction("Library scan") { repository.refresh() }

    // ---- Lookups -----------------------------------------------------------

    fun album(id: Long): Album? = repository.albumById(id)
    fun artist(id: Long): Artist? = repository.artistById(id)

    /**
     * The artist a song is filed under, for the "go to artist" action.
     *
     * Resolved by membership rather than from an id on the song: artists are keyed by a hash of
     * the album-artist name (there is no stable MediaStore artist id to use), and with separators
     * enabled a song can be filed under several. This returns the first that actually contains it.
     */
    fun artistForSong(song: Song): Artist? =
        library.value.artists.firstOrNull { artist -> artist.songs.any { it.id == song.id } }
    fun genre(id: Long): Genre? = library.value.genres.firstOrNull { it.id == id }
    fun playlist(id: String): Playlist? = playlists.value.firstOrNull { it.id == id }
    fun songsOf(playlist: Playlist): List<Song> = repository.songsByIds(playlist.songIds)
    fun songsInFolder(path: String): List<Song> = repository.songsInFolder(path)
    fun currentSong(): Song? = playerState.value.currentSongId?.let(repository::songById)

    // ---- Artwork -----------------------------------------------------------

    /**
     * A song always shows the artwork embedded in its own file.
     *
     * Album and artist overrides deliberately do not apply here: replacing an album's image
     * changes how that album is represented, not what any individual track looks like. A song's
     * thumbnail stays whatever its tags say until those tags change.
     */
    fun artForSong(song: Song): ArtRequest = ArtRequest(
        overridePath = null,
        audioPath = song.path,
        version = song.dateModifiedSeconds.toString(),
    )

    fun artForAlbum(album: Album): ArtRequest {
        val override = artworkOverrides.value.albumArt[album.id]
        val song = album.songs.firstOrNull()
        return ArtRequest(
            overridePath = override,
            audioPath = song?.path,
            version = override ?: song?.dateModifiedSeconds?.toString().orEmpty(),
        )
    }

    fun artForArtist(artist: Artist): ArtRequest {
        val override = artworkOverrides.value.artistArt[artist.id]
        val song = artist.songs.firstOrNull()
        return ArtRequest(
            overridePath = override,
            audioPath = song?.path,
            version = override ?: song?.dateModifiedSeconds?.toString().orEmpty(),
        )
    }

    fun clearMessage() { _messages.value = null }

    fun setAlbumArt(albumId: Long, uri: Uri) =
        viewModelScope.launch {
            repository.artwork.setAlbumArt(albumId, uri)?.let {
                _messages.value = "Couldn't set album image — $it"
            }
        }

    fun clearAlbumArt(albumId: Long) =
        viewModelScope.launch { repository.artwork.clearAlbumArt(albumId) }

    fun setArtistArt(artistId: Long, uri: Uri) =
        viewModelScope.launch {
            repository.artwork.setArtistArt(artistId, uri)?.let {
                _messages.value = "Couldn't set artist image — $it"
            }
        }

    fun clearArtistArt(artistId: Long) =
        viewModelScope.launch { repository.artwork.clearArtistArt(artistId) }

    // ---- Folders -----------------------------------------------------------

    val folderMode = preferences.folderMode.asState(FolderMode.EXCLUDE)

    fun setFolderMode(mode: FolderMode) = edit { setFolderMode(mode) }

    fun addFolder(path: String) =
        viewModelScope.launch { repository.preferences.setFolderExcluded(path, true) }

    fun removeFolder(path: String) =
        viewModelScope.launch { repository.preferences.setFolderExcluded(path, false) }

    fun clearFolders() =
        viewModelScope.launch { repository.preferences.setExcludedFolders(emptySet()) }

    fun reportMessage(text: String) { _messages.value = text }

    private fun reportFailure(action: String, failure: Throwable) {
        _messages.value = "$action failed: ${failure.message ?: failure::class.simpleName}"
    }

    private fun launchAction(action: String, block: suspend () -> Unit) =
        viewModelScope.launch { runCatching { block() }.onFailure { reportFailure(action, it) } }

    // ---- Favourites & playback rate ----------------------------------------

    val favourites = preferences.favourites.asState(emptySet())

    val playStats = repository.playStats.stats

    /** Songs marked favourite, in library order. */
    val favouriteSongs: StateFlow<List<Song>> =
        combine(library, favourites) { lib, ids -> lib.songs.filter { it.id in ids } }
            .asState(emptyList())

    /** Most played first, excluding anything never played. */
    val mostPlayed: StateFlow<List<Song>> =
        combine(library, playStats) { lib, stats ->
            lib.songs
                .filter { (stats[it.id]?.count ?: 0) > 0 }
                .sortedByDescending { stats[it.id]?.count ?: 0 }
        }.asState(emptyList())

    fun playCount(songs: List<Song>): Int = playStats.value.totalPlays(songs)

    fun isFavourite(song: Song) = song.id in favourites.value

    fun toggleFavourite(song: Song) =
        launchAction("Favourite update") {
            preferences.setFavourite(song, song.id !in favourites.value)
        }

    fun setSpeed(speed: Float) = player.setSpeed(speed)

    // ---- Sorting & metadata ------------------------------------------------

    fun setSongSort(order: SortOrder) =
        viewModelScope.launch { repository.preferences.setSongSort(order) }

    fun setGroupSort(order: GroupSortOrder) =
        viewModelScope.launch { repository.preferences.setGroupSort(order) }

    fun setDetailSort(order: SortOrder?) =
        viewModelScope.launch { repository.preferences.setDetailSort(order) }

    fun setDetailSortDescending(descending: Boolean) =
        viewModelScope.launch { repository.preferences.setDetailSortDescending(descending) }

    // ---- Genres ------------------------------------------------------------

    val genreOverrides = repository.genres.overrides

    /** Every genre currently in use, offered as suggestions when assigning one. */
    val knownGenres: StateFlow<List<String>> =
        library.map { lib -> lib.genres.map { it.name }.sorted() }.asState(emptyList())

    /**
     * Assigns a genre to songs. A blank genre clears the assignment and lets each song fall back
     * to its own tag. Audio files are not modified — see [dev.tune.player.data.GenreStore].
     */
    fun assignGenre(songIds: Collection<Long>, genre: String) =
        launchAction("Genre update") {
            repository.genres.assign(repository.songsByIds(songIds.toList()), genre)
        }

    fun assignGenreToSelection(genre: String) {
        val ids = _selection.value
        if (ids.isNotEmpty()) assignGenre(ids, genre)
        clearSelection()
    }

    fun setSortDescending(descending: Boolean) =
        viewModelScope.launch { repository.preferences.setSortDescending(descending) }

    fun setGroupSortDescending(descending: Boolean) =
        viewModelScope.launch { repository.preferences.setGroupSortDescending(descending) }

    fun setGridView(grid: Boolean) =
        viewModelScope.launch { repository.preferences.setGridView(grid) }

    fun showMetadata(song: Song) { _metadataSong.value = song }
    fun dismissMetadata() { _metadataSong.value = null }

    // ---- Sorted group lists ------------------------------------------------
    //
    // Each tab reads its own pre-sorted flow rather than sorting during composition, so scrolling
    // never pays for a re-sort. They all depend on repository.playStats.stats directly instead of
    // the playStats property below — property initialisers run in declaration order, and these are
    // declared first.

    private data class SortInputs(
        val order: GroupSortOrder,
        val stats: Map<Long, PlayStat>,
        val ignoreArticles: Boolean,
        val descending: Boolean,
    ) {
        val collator get() = NameCollator(ignoreArticles)
    }

    private val sortInputs =
        combine(
            repository.preferences.groupSort,
            repository.playStats.stats,
            repository.preferences.autoSortNames,
            repository.preferences.groupSortDescending,
        ) { order, stats, articles, descending ->
            val usesStats = order == GroupSortOrder.MOST_PLAYED ||
                order == GroupSortOrder.RECENTLY_PLAYED
            SortInputs(order, if (usesStats) stats else emptyMap(), articles, descending)
        }.distinctUntilChanged()

    val sortedAlbums: StateFlow<List<Album>> =
        combine(repository.groupedLibrary, sortInputs) { lib, s ->
            lib.albums.sortedWith(s.order.albumComparator(s.collator, s.stats, s.descending))
        }.flowOn(Dispatchers.Default).asState(emptyList())

    val sortedArtists: StateFlow<List<Artist>> =
        combine(repository.groupedLibrary, sortInputs) { lib, s ->
            lib.artists.sortedWith(s.order.artistComparator(s.collator, s.stats, s.descending))
        }.flowOn(Dispatchers.Default).asState(emptyList())

    val sortedGenres: StateFlow<List<Genre>> =
        combine(repository.groupedLibrary, sortInputs) { lib, s ->
            lib.genres.sortedWith(s.order.genreComparator(s.collator, s.stats, s.descending))
        }.flowOn(Dispatchers.Default).asState(emptyList())

    val sortedFolders: StateFlow<List<Folder>> =
        combine(repository.groupedLibrary, sortInputs) { lib, s ->
            lib.folders.sortedWith(s.order.folderComparator(s.collator, s.descending))
        }.flowOn(Dispatchers.Default).asState(emptyList())

    val sortedPlaylists: StateFlow<List<Playlist>> =
        combine(repository.playlists.playlists, sortInputs) { lists, s ->
            lists.sortedWith(
                s.order.playlistComparator(s.collator, s.stats, s.descending) {
                    repository.songsByIds(it.songIds)
                }
            )
        }.flowOn(Dispatchers.Default).asState(emptyList())

    // ---- Duplicates --------------------------------------------------------

    /** Tracks that appear more than once. Reported only — nothing here touches a file. */
    val duplicates: StateFlow<List<DuplicateGroup>> =
        repository.groupedLibrary.map { findDuplicates(it.songs) }
            .flowOn(Dispatchers.Default)
            .asState(emptyList())

    // ---- Playlists ---------------------------------------------------------

    fun createPlaylist(name: String, songIds: List<Long> = emptyList()) =
        launchAction("Create playlist") {
            val songs = repository.songsByIds(songIds)
            repository.playlists.create(name, songs.map { it.id }, songs.map { it.stableKey })
        }

    fun deletePlaylist(id: String) =
        launchAction("Delete playlist") { repository.playlists.delete(id) }

    fun renamePlaylist(id: String, name: String) =
        launchAction("Rename playlist") { repository.playlists.rename(id, name) }

    fun addToPlaylist(playlistId: String, songIds: List<Long>) =
        launchAction("Update playlist") {
            val songs = repository.songsByIds(songIds)
            repository.playlists.addSongs(playlistId, songs.map { it.id }, songs.map { it.stableKey })
        }

    fun removeFromPlaylist(playlistId: String, songId: Long) =
        launchAction("Update playlist") {
            repository.playlists.removeSong(
                playlistId,
                songId,
                repository.songById(songId)?.stableKey,
            )
        }

    fun setSleepTimer(minutes: Int?) = player.setSleepTimer(minutes?.times(60_000L))

    fun exportBackup(uri: Uri) = launchAction("Backup export") {
        repository.exportBackup(uri)
        _messages.value = "Tune backup exported"
    }

    fun importBackup(uri: Uri) = launchAction("Backup import") {
        repository.importBackup(uri)
        _messages.value = "Tune backup imported"
    }

    fun exportPlaylist(uri: Uri, playlist: Playlist) = launchAction("Playlist export") {
        repository.exportM3u(uri, playlist)
        _messages.value = "Playlist exported"
    }

    fun importPlaylist(uri: Uri) = launchAction("Playlist import") {
        val playlist = repository.importM3u(uri)
        _messages.value = "Imported ${playlist.name}"
    }

    // ---- Playback ----------------------------------------------------------

    /**
     * Plays [songs] from [startIndex], honouring the "tapping a song plays" preference: the list
     * as given, the whole library seeded at the tapped song, or that song alone.
     */
    fun playAll(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val index = startIndex.coerceIn(songs.indices)
        when (playInListWith.value) {
            PlayInMode.FROM_LIST -> player.play(songs, index)
            PlayInMode.SINGLE -> player.play(listOf(songs[index]))
            PlayInMode.FROM_LIBRARY -> {
                val all = library.value.songs
                val at = all.indexOfFirst { it.id == songs[index].id }
                if (at >= 0) player.play(all, at) else player.play(songs, index)
            }
        }
    }

    fun playShuffled(songs: List<Song>) {
        if (songs.isEmpty()) return
        player.play(songs.shuffled())
    }

    private companion object {
        const val RESTORE_ATTEMPTS = 10
        const val RESTORE_RETRY_MS = 300L
        const val PLAY_COUNT_AFTER_MS = 30_000L

        /** How often the advancing playback position is written to the resume snapshot. */
        const val RESUME_POSITION_SAVE_MS = 5_000L

        /** Quiet period after a keystroke before the search filter re-runs. */
        const val SEARCH_DEBOUNCE_MS = 150L
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
