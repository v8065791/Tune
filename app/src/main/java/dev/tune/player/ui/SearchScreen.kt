package dev.tune.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tune.player.data.Album
import dev.tune.player.data.Artist
import dev.tune.player.data.Genre
import dev.tune.player.data.Song
import dev.tune.player.ui.components.Artwork
import dev.tune.player.ui.components.EmptyState
import dev.tune.player.ui.components.SongRow

/** Search across songs, albums, artists and genres, grouped by kind. */
@Composable
fun SearchScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onGenreClick: (Genre) -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    // Opening search should put the caret in the field — nobody navigates here to browse.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = vm::setSearchQuery,
                        placeholder = { Text("Search library") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { vm.setSearchQuery("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)

        when {
            query.isBlank() -> EmptyState("Search for songs, albums, artists or genres.", modifier)
            results.isEmpty -> EmptyState("Nothing matches \"$query\".", modifier)
            else -> LazyColumn(modifier = modifier) {
                if (results.artists.isNotEmpty()) {
                    item { ResultHeader("Artists") }
                    items(results.artists, key = { "artist-${it.id}" }) { artist ->
                        EntityRow(
                            title = artist.name,
                            subtitle = "${artist.albums.size} albums · ${artist.songs.size} songs",
                            rounded = true,
                            art = vm.artForArtist(artist),
                            onClick = { onArtistClick(artist) },
                        )
                    }
                }

                if (results.albums.isNotEmpty()) {
                    item { ResultHeader("Albums") }
                    items(results.albums, key = { "album-${it.id}" }) { album ->
                        EntityRow(
                            title = album.name,
                            subtitle = album.artist,
                            rounded = false,
                            art = vm.artForAlbum(album),
                            onClick = { onAlbumClick(album) },
                        )
                    }
                }

                if (results.genres.isNotEmpty()) {
                    item { ResultHeader("Genres") }
                    items(results.genres, key = { "genre-${it.id}" }) { genre ->
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
                            Text(
                                genre.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }

                if (results.songs.isNotEmpty()) {
                    item { ResultHeader("Songs") }
                    itemsIndexed(results.songs, key = { _, s -> "song-${s.id}" }) { index, song ->
                        SongRow(
                            song = song,
                            art = vm.artForSong(song),
                            highlighted = song.id == playerState.currentSongId,
                            onClick = { vm.playAll(results.songs, index) },
                            onMenuClick = { onSongMenu(song) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun EntityRow(
    title: String,
    subtitle: String,
    rounded: Boolean,
    art: dev.tune.player.art.ArtRequest,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art = art, rounded = rounded, modifier = Modifier.size(48.dp))
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
