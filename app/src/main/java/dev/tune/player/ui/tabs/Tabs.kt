package dev.tune.player.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tune.player.data.Album
import dev.tune.player.data.Artist
import dev.tune.player.data.Folder
import dev.tune.player.data.Genre
import dev.tune.player.data.Playlist
import dev.tune.player.data.Song
import dev.tune.player.ui.MainViewModel
import dev.tune.player.ui.components.EmptyState
import dev.tune.player.ui.components.GridCard
import dev.tune.player.ui.components.SongRow

/** Bottom padding so the mini player never covers the last list item. */
private val ListBottomPadding = PaddingValues(bottom = 96.dp)

@Composable
fun SongsTab(
    songs: List<Song>,
    vm: MainViewModel,
    currentSongId: Long?,
    onSongMenu: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) {
        EmptyState("No songs found.\nCheck your folder selection in Settings.", modifier)
        return
    }
    val selection by vm.selection.collectAsState()
    val selecting = selection.isNotEmpty()

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
        // Keyed by song id so scroll position survives a re-sort.
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            SongRow(
                song = song,
                art = vm.artForSong(song),
                highlighted = song.id == currentSongId,
                // While selecting, a tap toggles instead of playing.
                onClick = { if (selecting) vm.toggleSelection(song) else vm.playAll(songs, index) },
                onMenuClick = { if (selecting) vm.toggleSelection(song) else onSongMenu(song) },
                selected = if (selecting) song.id in selection else null,
                // Long-press begins a bulk selection.
                onLongClick = { vm.toggleSelection(song) },
            )
        }
    }
}

@Composable
fun AlbumsTab(
    albums: List<Album>,
    vm: MainViewModel,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) {
        EmptyState("No albums found.", modifier)
        return
    }
    // Subscribing to the overrides here is what makes the grid refresh after a custom image is
    // set or cleared — artForAlbum reads the store directly and can't trigger recomposition.
    val overrides by vm.artworkOverrides.collectAsState()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = ListBottomPadding,
    ) {
        items(albums, key = { it.id }) { album ->
            GridCard(
                art = remember(album.id, overrides) { vm.artForAlbum(album) },
                title = album.name,
                subtitle = if (album.year > 0) "${album.artist} · ${album.year}" else album.artist,
                rounded = false,
                onClick = { onAlbumClick(album) },
            )
        }
    }
}

@Composable
fun ArtistsTab(
    artists: List<Artist>,
    vm: MainViewModel,
    onArtistClick: (Artist) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artists.isEmpty()) {
        EmptyState("No artists found.", modifier)
        return
    }
    val overrides by vm.artworkOverrides.collectAsState()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = ListBottomPadding,
    ) {
        items(artists, key = { it.id }) { artist ->
            GridCard(
                art = remember(artist.id, overrides) { vm.artForArtist(artist) },
                title = artist.name,
                subtitle = "${artist.albums.size} albums · ${artist.songs.size} songs",
                rounded = true,
                onClick = { onArtistClick(artist) },
            )
        }
    }
}

@Composable
fun PlaylistsTab(
    playlists: List<Playlist>,
    onPlaylistClick: (Playlist) -> Unit,
    onCreatePlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
        item {
            TextButton(
                onClick = onCreatePlaylist,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("New playlist", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    text = "No playlists yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        items(playlists, key = { it.id }) { playlist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlaylistClick(playlist) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${playlist.songIds.size} songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun GenresTab(
    genres: List<Genre>,
    onGenreClick: (Genre) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (genres.isEmpty()) {
        EmptyState("No genres found.\nYour files may not carry a genre tag.", modifier)
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
        items(genres, key = { it.id }) { genre ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGenreClick(genre) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Category,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(genre.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        "${genre.songs.size} songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun FoldersTab(
    folders: List<Folder>,
    onFolderClick: (Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (folders.isEmpty()) {
        EmptyState("No folders with music.", modifier)
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
        items(folders, key = { it.path }) { folder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onFolderClick(folder) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier.padding(start = 16.dp).weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        text = folder.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${folder.songCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
