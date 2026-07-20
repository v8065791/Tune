package dev.tune.player.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.tune.player.TuneApplication
import dev.tune.player.art.ArtRequest
import dev.tune.player.data.Album
import dev.tune.player.data.Artist
import dev.tune.player.data.CoverMode
import dev.tune.player.data.Folder
import dev.tune.player.data.Genre
import dev.tune.player.data.HomeTab
import dev.tune.player.data.PlayInMode
import dev.tune.player.data.Playlist
import dev.tune.player.data.Song
import dev.tune.player.data.SortOrder
import dev.tune.player.data.ThemeMode
import dev.tune.player.data.UserPreferences
import dev.tune.player.player.PlayerController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val observing = preferences.observing.asState(true)
    val autoSortNames = preferences.autoSortNames.asState(true)
    val hideCollaborators = preferences.hideCollaborators.asState(false)
    val separators = preferences.separators.asState("")
    val playInListWith = preferences.playInListWith.asState(PlayInMode.FROM_LIST)
    val rewindOnPrevious = preferences.rewindOnPrevious.asState(true)
    val headsetAutoplay = preferences.headsetAutoplay.asState(false)
    val rememberPlayback = preferences.rememberPlayback.asState(true)
    val keepShuffle = preferences.keepShuffle.asState(true)

    /** Runs a preference write off the UI thread; every settings toggle funnels through here. */
    fun edit(block: suspend UserPreferences.() -> Unit) =
        viewModelScope.launch { preferences.block() }

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
    }

    fun onPermissionGranted() {
        if (_permissionGranted.value) return
        _permissionGranted.value = true
        viewModelScope.launch { repository.initialise() }
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

    fun setFolderExcluded(folder: Folder, excluded: Boolean) =
        viewModelScope.launch { repository.toggleFolder(folder.path, excluded) }

    fun includeAllFolders() =
        viewModelScope.launch { repository.preferences.setExcludedFolders(emptySet()) }

    // ---- Sorting & metadata ------------------------------------------------

    fun setSongSort(order: SortOrder) =
        viewModelScope.launch { repository.preferences.setSongSort(order) }

    fun showMetadata(song: Song) { _metadataSong.value = song }
    fun dismissMetadata() { _metadataSong.value = null }

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

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
