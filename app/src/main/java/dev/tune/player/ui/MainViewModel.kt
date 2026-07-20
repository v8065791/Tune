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
import dev.tune.player.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as TuneApplication).repository

    val player = PlayerController(application, viewModelScope)

    val library = repository.library
    val isScanning = repository.isScanning
    val allFolders = repository.allFolders
    val playlists = repository.playlists.playlists
    val artworkOverrides = repository.artwork.overrides
    val playerState = player.state

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
    val headsetAutoplay = preferences.headsetAutoplay.asState(false)
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
     */
    val searchResults: StateFlow<SearchResults> =
        combine(library, _searchQuery) { lib, query ->
            val term = query.trim().lowercase()
            if (term.isBlank()) SearchResults()
            else SearchResults(
                songs = lib.songs.filter {
                    it.title.lowercase().contains(term) ||
                        it.artist.lowercase().contains(term) ||
                        it.album.lowercase().contains(term)
                },
                albums = lib.albums.filter {
                    it.name.lowercase().contains(term) || it.artist.lowercase().contains(term)
                },
                artists = lib.artists.filter { it.name.lowercase().contains(term) },
                genres = lib.genres.filter { it.name.lowercase().contains(term) },
            )
        }.asState(SearchResults())

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    /** Song whose full metadata sheet is open, if any. */
    private val _metadataSong = MutableStateFlow<Song?>(null)
    val metadataSong: StateFlow<Song?> = _metadataSong.asStateFlow()

    init {
        player.connect()
        // Mirror playback prefs onto the controller as they change.
        viewModelScope.launch {
            preferences.rewindOnPrevious.collect { player.rewindOnPrevious = it }
        }


        // Count a play when the track actually changes, not when it is queued.
        viewModelScope.launch {
            var lastCounted: Long? = null
            playerState.collect { state ->
                val id = state.currentSongId
                if (id != null && state.isPlaying && id != lastCounted) {
                    lastCounted = id
                    repository.playStats.recordPlay(id, System.currentTimeMillis())
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
                val info = withContext(Dispatchers.IO) { ReplayGainReader.read(song.path) }
                player.setVolume(info.volumeFor(mode))
            }
        }

        // Persist the queue as it changes, so it survives the process being killed.
        viewModelScope.launch {
            playerState.collect { state ->
                if (state.queueIds.isNotEmpty() && preferences.rememberPlayback.first()) {
                    preferences.saveResumeState(
                        state.queueIds,
                        state.queueIndex,
                        state.positionMs,
                    )
                }
            }
        }
    }

    /**
     * Puts back the queue from the last session, paused. Waits for the library so ids can be
     * resolved, and for the media controller to finish connecting.
     */
    private fun restorePlayback() = viewModelScope.launch {
        if (!preferences.rememberPlayback.first()) return@launch
        val saved = preferences.resumeState.first() ?: return@launch
        val songs = library.first { it.songs.isNotEmpty() }.songs.associateBy { it.id }
        val queue = saved.queue.mapNotNull { songs[it] }
        if (queue.isEmpty()) return@launch

        // The controller connects asynchronously; retry briefly rather than racing it.
        repeat(RESTORE_ATTEMPTS) {
            if (playerState.value.queueSize > 0) return@launch
            player.restore(queue, saved.index, saved.positionMs)
            delay(RESTORE_RETRY_MS)
        }
    }

    fun onPermissionGranted() {
        if (_permissionGranted.value) return
        _permissionGranted.value = true
        viewModelScope.launch { repository.initialise() }
        restorePlayback()
    }

    fun refresh() = viewModelScope.launch { repository.refresh() }

    // ---- Lookups -----------------------------------------------------------

    fun album(id: Long): Album? = repository.albumById(id)
    fun artist(id: Long): Artist? = repository.artistById(id)
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
    fun artForSong(song: Song): ArtRequest = ArtRequest(overridePath = null, audioPath = song.path)

    fun artForAlbum(album: Album): ArtRequest {
        val override = artworkOverrides.value.albumArt[album.id]
        return ArtRequest(overridePath = override, audioPath = album.songs.firstOrNull()?.path)
    }

    fun artForArtist(artist: Artist): ArtRequest {
        val override = artworkOverrides.value.artistArt[artist.id]
        return ArtRequest(overridePath = override, audioPath = artist.songs.firstOrNull()?.path)
    }

    /** Transient user-facing messages, e.g. why an artwork change failed. */
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

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
        viewModelScope.launch { preferences.setFavourite(song.id, song.id !in favourites.value) }

    fun setSpeed(speed: Float) = player.setSpeed(speed)

    // ---- Sorting & metadata ------------------------------------------------

    fun setSongSort(order: SortOrder) =
        viewModelScope.launch { repository.preferences.setSongSort(order) }

    fun setGroupSort(order: GroupSortOrder) =
        viewModelScope.launch { repository.preferences.setGroupSort(order) }

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
        val collator: NameCollator,
        val descending: Boolean,
    )

    private val sortInputs =
        combine(
            repository.preferences.groupSort,
            repository.playStats.stats,
            repository.preferences.autoSortNames,
            repository.preferences.groupSortDescending,
        ) { order, stats, articles, descending ->
            SortInputs(order, stats, NameCollator(articles), descending)
        }

    val sortedAlbums: StateFlow<List<Album>> =
        combine(library, sortInputs) { lib, s ->
            lib.albums.sortedWith(s.order.albumComparator(s.collator, s.stats, s.descending))
        }.flowOn(Dispatchers.Default).asState(emptyList())

    val sortedArtists: StateFlow<List<Artist>> =
        combine(library, sortInputs) { lib, s ->
            lib.artists.sortedWith(s.order.artistComparator(s.collator, s.stats, s.descending))
        }.flowOn(Dispatchers.Default).asState(emptyList())

    val sortedGenres: StateFlow<List<Genre>> =
        combine(library, sortInputs) { lib, s ->
            lib.genres.sortedWith(s.order.genreComparator(s.collator, s.stats, s.descending))
        }.flowOn(Dispatchers.Default).asState(emptyList())

    val sortedFolders: StateFlow<List<Folder>> =
        combine(library, sortInputs) { lib, s ->
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
        library.map { findDuplicates(it.songs) }
            .flowOn(Dispatchers.Default)
            .asState(emptyList())

    // ---- Playlists ---------------------------------------------------------

    fun createPlaylist(name: String, songIds: List<Long> = emptyList()) =
        viewModelScope.launch { repository.playlists.create(name, songIds) }

    fun deletePlaylist(id: String) =
        viewModelScope.launch { repository.playlists.delete(id) }

    fun renamePlaylist(id: String, name: String) =
        viewModelScope.launch { repository.playlists.rename(id, name) }

    fun addToPlaylist(playlistId: String, songIds: List<Long>) =
        viewModelScope.launch { repository.playlists.addSongs(playlistId, songIds) }

    fun removeFromPlaylist(playlistId: String, songId: Long) =
        viewModelScope.launch { repository.playlists.removeSong(playlistId, songId) }

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
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
