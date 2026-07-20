package dev.tune.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tune.player.data.Album
import dev.tune.player.data.Artist
import dev.tune.player.data.Folder
import dev.tune.player.data.Genre
import dev.tune.player.data.HomeTab
import dev.tune.player.data.Playlist
import dev.tune.player.data.Song
import dev.tune.player.data.SortOrder
import dev.tune.player.ui.tabs.AlbumsTab
import dev.tune.player.ui.tabs.ArtistsTab
import dev.tune.player.ui.tabs.FoldersTab
import dev.tune.player.ui.tabs.GenresTab
import dev.tune.player.ui.tabs.PlaylistsTab
import dev.tune.player.ui.tabs.SongsTab
import kotlinx.coroutines.launch

/** Tabs whose contents are a flat song list, and so respond to the sort order. */
private val SORTABLE_TABS = setOf(HomeTab.SONGS, HomeTab.FAVOURITES)

/** Contextual toolbar shown in place of the normal one while songs are selected. */
@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "Clear selection")
            }
        },
        actions = {
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play selection")
            }
            IconButton(onClick = onQueue) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Add selection to queue",
                )
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = "Add selection to playlist",
                )
            }
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Default.SelectAll, contentDescription = "Select all")
            }
        },
    )
}

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onGenreClick: (Genre) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onFolderClick: (Folder) -> Unit,
    onSongMenu: (Song) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onExpandPlayer: () -> Unit,
    onSelectionToPlaylist: () -> Unit,
) {
    val library by vm.library.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val playerState by vm.playerState.collectAsState()
    val isScanning by vm.isScanning.collectAsState()
    val sort by vm.songSort.collectAsState()
    val tabs by vm.homeTabs.collectAsState()

    // pageCount is read lazily, so removing a tab in settings updates the pager in place.
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    var sortMenuOpen by remember { mutableStateOf(false) }

    val currentSong = remember(playerState.currentSongId, library) {
        library.songs.firstOrNull { it.id == playerState.currentSongId }
    }
    val currentTab = tabs.getOrNull(pagerState.currentPage)

    val selection by vm.selection.collectAsState()

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) SelectionBar(
                count = selection.size,
                onClear = { vm.clearSelection() },
                onSelectAll = { vm.selectAll(library.songs) },
                onPlay = { vm.playSelection(library.songs) },
                onQueue = { vm.queueSelection(library.songs) },
                onAddToPlaylist = onSelectionToPlaylist,
            ) else TopAppBar(
                title = { Text("Tune") },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    // Sorting applies to any tab that renders a flat song list.
                    if (currentTab in SORTABLE_TABS) {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort songs")
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            SortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(order.label) },
                                    trailingIcon = {
                                        if (order == sort) Icon(Icons.Default.Check, null)
                                    },
                                    onClick = {
                                        vm.setSongSort(order)
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan library")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            ScrollableTabRow(selectedTabIndex = pagerState.currentPage, edgePadding = 8.dp) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(tab.label) },
                    )
                }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (tabs.getOrNull(page)) {
                    HomeTab.SONGS -> SongsTab(
                        songs = library.songs,
                        vm = vm,
                        currentSongId = playerState.currentSongId,
                        onSongMenu = onSongMenu,
                    )
                    HomeTab.ALBUMS -> AlbumsTab(library.albums, vm, onAlbumClick)
                    HomeTab.ARTISTS -> ArtistsTab(library.artists, vm, onArtistClick)
                    HomeTab.GENRES -> GenresTab(library.genres, onGenreClick)
                    HomeTab.FAVOURITES -> {
                        val favourites by vm.favouriteSongs.collectAsState()
                        SongsTab(
                            songs = favourites,
                            vm = vm,
                            currentSongId = playerState.currentSongId,
                            onSongMenu = onSongMenu,
                            emptyMessage = "No favourites yet.\nTap the heart on the player.",
                        )
                    }
                    HomeTab.MOST_PLAYED -> {
                        val mostPlayed by vm.mostPlayed.collectAsState()
                        SongsTab(
                            songs = mostPlayed,
                            vm = vm,
                            currentSongId = playerState.currentSongId,
                            onSongMenu = onSongMenu,
                            emptyMessage = "Nothing played yet.",
                        )
                    }
                    HomeTab.PLAYLISTS -> PlaylistsTab(playlists, onPlaylistClick, onCreatePlaylist)
                    HomeTab.FOLDERS -> FoldersTab(library.folders, onFolderClick)
                    null -> Unit
                }
            }
        }
    }
}
