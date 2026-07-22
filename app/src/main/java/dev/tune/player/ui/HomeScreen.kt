package dev.tune.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import dev.tune.player.data.GroupSortOrder
import dev.tune.player.data.HomeTab
import dev.tune.player.data.Playlist
import dev.tune.player.data.Song
import dev.tune.player.data.SortOrder
import dev.tune.player.ui.components.SelectionTopBar
import dev.tune.player.ui.tabs.AlbumsTab
import dev.tune.player.ui.tabs.ArtistsTab
import dev.tune.player.ui.tabs.FoldersTab
import dev.tune.player.ui.tabs.GenresTab
import dev.tune.player.ui.tabs.PlaylistsTab
import dev.tune.player.ui.tabs.SongsTab
import kotlinx.coroutines.launch

/**
 * Tabs whose contents are a flat song list. These use [SortOrder]; every other tab lists groups
 * and uses [GroupSortOrder] instead.
 *
 * Most played is deliberately absent: it is already ordered by play count, and letting the sort
 * button reorder it would leave the tab not doing what its name says.
 */
private val SONG_TABS = setOf(HomeTab.SONGS, HomeTab.FAVOURITES)

/** One entry in the sort menu, ticked when it is the active order. */
@Composable
private fun SortItem(label: String, active: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { if (active) Icon(Icons.Default.Check, contentDescription = null) },
        onClick = onClick,
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
    onSelectionToPlaylist: () -> Unit,
    onSelectionSetGenre: () -> Unit,
) {
    val library by vm.library.collectAsStateWithLifecycle()
    // Group tabs read pre-sorted flows so the chosen order applies without sorting during layout.
    val albums by vm.sortedAlbums.collectAsStateWithLifecycle()
    val artists by vm.sortedArtists.collectAsStateWithLifecycle()
    val genres by vm.sortedGenres.collectAsStateWithLifecycle()
    val folders by vm.sortedFolders.collectAsStateWithLifecycle()
    val playlists by vm.sortedPlaylists.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val isScanning by vm.isScanning.collectAsStateWithLifecycle()
    val sort by vm.songSort.collectAsStateWithLifecycle()
    val groupSort by vm.groupSort.collectAsStateWithLifecycle()
    val sortDescending by vm.sortDescending.collectAsStateWithLifecycle()
    val groupSortDescending by vm.groupSortDescending.collectAsStateWithLifecycle()
    val tabs by vm.homeTabs.collectAsStateWithLifecycle()
    val grid by vm.gridView.collectAsStateWithLifecycle()

    // pageCount is read lazily, so removing a tab in settings updates the pager in place.
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    var sortMenuOpen by remember { mutableStateOf(false) }

    val currentSong = remember(playerState.currentSongId, library) {
        library.songs.firstOrNull { it.id == playerState.currentSongId }
    }
    val currentTab = tabs.getOrNull(pagerState.currentPage)

    val selection by vm.selection.collectAsStateWithLifecycle()

    val favourites by vm.favouriteSongs.collectAsStateWithLifecycle()
    val mostPlayed by vm.mostPlayed.collectAsStateWithLifecycle()

    // Which song list the current tab is showing — what the selection toolbar acts on.
    val visibleSongs = when (currentTab) {
        HomeTab.FAVOURITES -> favourites
        HomeTab.MOST_PLAYED -> mostPlayed
        else -> library.songs
    }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) SelectionTopBar(
                count = selection.size,
                onClear = { vm.clearSelection() },
                // Scoped to the tab in view: "select all" on Favourites means the favourites,
                // not the entire library.
                onSelectAll = { vm.selectAll(visibleSongs) },
                onPlay = { vm.playSelection(visibleSongs) },
                onQueue = { vm.queueSelection(visibleSongs) },
                onAddToPlaylist = onSelectionToPlaylist,
                onSetGenre = onSelectionSetGenre,
            ) else TopAppBar(
                title = { Text("Tune") },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    // Every tab sorts; song tabs and group tabs just use different orders.
                    if (currentTab != null && currentTab != HomeTab.MOST_PLAYED) {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                        }
                        val songTab = currentTab in SONG_TABS
                        // Song lists and group tabs keep their own order and direction, so
                        // sorting albums by date doesn't silently reorder the songs tab too.
                        val descending = if (songTab) sortDescending else groupSortDescending
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            if (songTab) {
                                SortOrder.entries.forEach { order ->
                                    SortItem(order.label, order == sort) {
                                        vm.setSongSort(order)
                                        sortMenuOpen = false
                                    }
                                }
                            } else {
                                GroupSortOrder.entries.forEach { order ->
                                    SortItem(order.label, order == groupSort) {
                                        vm.setGroupSort(order)
                                        sortMenuOpen = false
                                    }
                                }
                            }

                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (descending) "Descending" else "Ascending") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (descending) Icons.Default.ArrowDownward
                                        else Icons.Default.ArrowUpward,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    if (songTab) vm.setSortDescending(!descending)
                                    else vm.setGroupSortDescending(!descending)
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                    IconButton(onClick = { vm.setGridView(!grid) }) {
                        Icon(
                            imageVector = if (grid) Icons.AutoMirrored.Filled.ViewList
                            else Icons.Default.GridView,
                            contentDescription = if (grid) "Show as a list" else "Show as a grid",
                        )
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
                        grid = grid,
                    )
                    HomeTab.ALBUMS -> AlbumsTab(albums, vm, onAlbumClick, grid)
                    HomeTab.ARTISTS -> ArtistsTab(artists, vm, onArtistClick, grid)
                    HomeTab.GENRES -> GenresTab(genres, vm, onGenreClick, grid)
                    HomeTab.FAVOURITES -> {
                        SongsTab(
                            songs = favourites,
                            vm = vm,
                            currentSongId = playerState.currentSongId,
                            onSongMenu = onSongMenu,
                            grid = grid,
                            emptyMessage = "No favourites yet.\nTap the heart on the player.",
                        )
                    }
                    HomeTab.MOST_PLAYED -> {
                        SongsTab(
                            songs = mostPlayed,
                            vm = vm,
                            currentSongId = playerState.currentSongId,
                            onSongMenu = onSongMenu,
                            grid = grid,
                            emptyMessage = "Nothing played yet.",
                        )
                    }
                    HomeTab.PLAYLISTS ->
                        PlaylistsTab(playlists, vm, onPlaylistClick, onCreatePlaylist, grid)
                    HomeTab.FOLDERS -> FoldersTab(folders, vm, onFolderClick, grid)
                    null -> Unit
                }
            }
        }
    }
}
