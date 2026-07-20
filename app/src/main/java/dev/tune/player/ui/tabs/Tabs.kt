package dev.tune.player.ui.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tune.player.art.ArtRequest
import dev.tune.player.data.Album
import dev.tune.player.data.Artist
import dev.tune.player.data.Folder
import dev.tune.player.data.Genre
import dev.tune.player.data.Playlist
import dev.tune.player.data.Song
import dev.tune.player.ui.MainViewModel
import dev.tune.player.ui.components.EmptyState
import dev.tune.player.ui.components.GridCard
import dev.tune.player.ui.components.ListCard
import dev.tune.player.ui.components.SongRow

/** Bottom padding so the mini player never covers the last list item. */
private val ListBottomPadding = PaddingValues(bottom = 96.dp)

private val GridColumns = GridCells.Adaptive(minSize = 150.dp)

/**
 * The generic body behind every group tab.
 *
 * Albums, artists, genres, playlists and folders differ only in what they call their title,
 * subtitle and artwork, so they share one implementation rather than five near-identical ones that
 * would drift as grid and list modes evolve.
 */
@Composable
private fun <T> GroupTab(
    items: List<T>,
    grid: Boolean,
    key: (T) -> Any,
    // Composable so call sites can subscribe to the artwork overrides via remember().
    art: @Composable (T) -> ArtRequest,
    title: (T) -> String,
    subtitle: (T) -> String,
    rounded: Boolean,
    emptyMessage: String,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    if (items.isEmpty() && header == null) {
        EmptyState(emptyMessage, modifier)
        return
    }

    if (grid) {
        LazyVerticalGrid(
            columns = GridColumns,
            modifier = modifier.fillMaxSize(),
            contentPadding = ListBottomPadding,
        ) {
            // A header spans the full row rather than sitting in the first cell.
            header?.let { content -> item(span = { GridItemSpan(maxLineSpan) }) { content() } }
            items(items, key = key) { entry ->
                GridCard(
                    art = art(entry),
                    title = title(entry),
                    subtitle = subtitle(entry),
                    rounded = rounded,
                    onClick = { onClick(entry) },
                )
            }
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
            header?.let { content -> item { content() } }
            items(items, key = key) { entry ->
                ListCard(
                    art = art(entry),
                    title = title(entry),
                    subtitle = subtitle(entry),
                    rounded = rounded,
                    onClick = { onClick(entry) },
                )
            }
        }
    }
}

@Composable
fun SongsTab(
    songs: List<Song>,
    vm: MainViewModel,
    currentSongId: Long?,
    onSongMenu: (Song) -> Unit,
    grid: Boolean,
    modifier: Modifier = Modifier,
    emptyMessage: String = "No songs found.\nCheck your folder selection in Settings.",
) {
    if (songs.isEmpty()) {
        EmptyState(emptyMessage, modifier)
        return
    }
    val selection by vm.selection.collectAsState()
    val selecting = selection.isNotEmpty()

    // A tap plays, unless a bulk selection is in progress — then it toggles instead.
    fun onTap(song: Song, index: Int) {
        if (selecting) vm.toggleSelection(song) else vm.playAll(songs, index)
    }

    if (grid) {
        LazyVerticalGrid(
            columns = GridColumns,
            modifier = modifier.fillMaxSize(),
            contentPadding = ListBottomPadding,
        ) {
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                GridCard(
                    art = vm.artForSong(song),
                    title = song.title,
                    subtitle = song.artist,
                    rounded = false,
                    onClick = { onTap(song, index) },
                    onLongClick = { vm.toggleSelection(song) },
                    selected = if (selecting) song.id in selection else null,
                    onMenuClick = { onSongMenu(song) },
                )
            }
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
            // Keyed by song id so scroll position survives a re-sort.
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    art = vm.artForSong(song),
                    highlighted = song.id == currentSongId,
                    onClick = { onTap(song, index) },
                    onMenuClick = { if (selecting) vm.toggleSelection(song) else onSongMenu(song) },
                    selected = if (selecting) song.id in selection else null,
                    // Long-press begins a bulk selection.
                    onLongClick = { vm.toggleSelection(song) },
                )
            }
        }
    }
}

@Composable
fun AlbumsTab(
    albums: List<Album>,
    vm: MainViewModel,
    onAlbumClick: (Album) -> Unit,
    grid: Boolean,
    modifier: Modifier = Modifier,
) {
    // Subscribing to the overrides here is what makes the tab refresh after a custom image is set
    // or cleared — artForAlbum reads the store directly and can't trigger recomposition.
    val overrides by vm.artworkOverrides.collectAsState()

    GroupTab(
        items = albums,
        grid = grid,
        key = { it.id },
        art = { remember(it.id, overrides) { vm.artForAlbum(it) } },
        title = { it.name },
        subtitle = { if (it.year > 0) "${it.artist} · ${it.year}" else it.artist },
        rounded = false,
        emptyMessage = "No albums found.",
        onClick = onAlbumClick,
        modifier = modifier,
    )
}

@Composable
fun ArtistsTab(
    artists: List<Artist>,
    vm: MainViewModel,
    onArtistClick: (Artist) -> Unit,
    grid: Boolean,
    modifier: Modifier = Modifier,
) {
    val overrides by vm.artworkOverrides.collectAsState()

    GroupTab(
        items = artists,
        grid = grid,
        key = { it.id },
        art = { remember(it.id, overrides) { vm.artForArtist(it) } },
        title = { it.name },
        subtitle = { "${it.albums.size} albums · ${it.songs.size} songs" },
        rounded = true,
        emptyMessage = "No artists found.",
        onClick = onArtistClick,
        modifier = modifier,
    )
}

@Composable
fun GenresTab(
    genres: List<Genre>,
    vm: MainViewModel,
    onGenreClick: (Genre) -> Unit,
    grid: Boolean,
    modifier: Modifier = Modifier,
) {
    GroupTab(
        items = genres,
        grid = grid,
        key = { it.id },
        // Genres have no artwork of their own, so borrow the first track's.
        art = { genre -> genre.songs.firstOrNull()?.let(vm::artForSong) ?: EmptyArt },
        title = { it.name },
        subtitle = { "${it.songs.size} songs" },
        rounded = false,
        emptyMessage = "No genres found.\nYour files may not carry a genre tag.",
        onClick = onGenreClick,
        modifier = modifier,
    )
}

@Composable
fun FoldersTab(
    folders: List<Folder>,
    vm: MainViewModel,
    onFolderClick: (Folder) -> Unit,
    grid: Boolean,
    modifier: Modifier = Modifier,
) {
    GroupTab(
        items = folders,
        grid = grid,
        key = { it.path },
        art = { folder ->
            remember(folder.path) {
                vm.songsInFolder(folder.path).firstOrNull()?.let(vm::artForSong) ?: EmptyArt
            }
        },
        title = { it.name },
        subtitle = { "${it.songCount} songs · ${it.path}" },
        rounded = false,
        emptyMessage = "No folders with music.",
        onClick = onFolderClick,
        modifier = modifier,
    )
}

@Composable
fun PlaylistsTab(
    playlists: List<Playlist>,
    vm: MainViewModel,
    onPlaylistClick: (Playlist) -> Unit,
    onCreatePlaylist: () -> Unit,
    grid: Boolean,
    modifier: Modifier = Modifier,
) {
    GroupTab(
        items = playlists,
        grid = grid,
        key = { it.id },
        art = { playlist ->
            remember(playlist.id, playlist.songIds) {
                vm.songsOf(playlist).firstOrNull()?.let(vm::artForSong) ?: EmptyArt
            }
        },
        title = { it.name },
        subtitle = { "${it.songIds.size} songs" },
        rounded = false,
        emptyMessage = "No playlists yet.",
        onClick = onPlaylistClick,
        modifier = modifier,
        header = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onCreatePlaylist,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("New playlist", modifier = Modifier.padding(start = 8.dp))
                }
                if (playlists.isEmpty()) {
                    Text(
                        text = "No playlists yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        },
    )
}

/** Stands in for a group with no track to borrow artwork from. */
private val EmptyArt = ArtRequest(overridePath = null, audioPath = null)
