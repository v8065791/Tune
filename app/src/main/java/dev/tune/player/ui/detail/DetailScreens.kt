package dev.tune.player.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tune.player.data.Song
import dev.tune.player.ui.MainViewModel
import dev.tune.player.ui.components.Artwork
import dev.tune.player.ui.components.EmptyState
import dev.tune.player.ui.components.SongRow
import dev.tune.player.ui.components.formatDuration

@Composable
fun AlbumDetailScreen(
    albumId: Long,
    vm: MainViewModel,
    onBack: () -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    // Re-read from the library flow so the screen follows rescans and folder changes.
    val library by vm.library.collectAsState()
    val overrides by vm.artworkOverrides.collectAsState()
    val album = library.albums.firstOrNull { it.id == albumId }
    val playerState by vm.playerState.collectAsState()

    if (album == null) {
        MissingScreen("Album not found", onBack)
        return
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.setAlbumArt(album.id, it) }
    }

    DetailScaffold(
        title = album.name,
        onBack = onBack,
        art = { modifier ->
            Artwork(art = vm.artForAlbum(album), cornerRadius = 12, modifier = modifier)
        },
        subtitle = buildString {
            append(album.artist)
            if (album.year > 0) append(" · ${album.year}")
            append(" · ${album.songs.size} songs · ${formatDuration(album.durationMs)}")
        },
        hasCustomArt = overrides.albumArt.containsKey(album.id),
        onEditArt = {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onClearArt = { vm.clearAlbumArt(album.id) },
        onPlay = { vm.playAll(album.songs) },
        onShuffle = { vm.playShuffled(album.songs) },
        songs = album.songs,
        currentSongId = playerState.currentSongId,
        vm = vm,
        onSongMenu = onSongMenu,
    )
}

@Composable
fun ArtistDetailScreen(
    artistId: Long,
    vm: MainViewModel,
    onBack: () -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val library by vm.library.collectAsState()
    val overrides by vm.artworkOverrides.collectAsState()
    val artist = library.artists.firstOrNull { it.id == artistId }
    val playerState by vm.playerState.collectAsState()

    if (artist == null) {
        MissingScreen("Artist not found", onBack)
        return
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.setArtistArt(artist.id, it) }
    }

    DetailScaffold(
        title = artist.name,
        onBack = onBack,
        art = { modifier ->
            Artwork(art = vm.artForArtist(artist), rounded = true, modifier = modifier)
        },
        subtitle = "${artist.albums.size} albums · ${artist.songs.size} songs",
        hasCustomArt = overrides.artistArt.containsKey(artist.id),
        onEditArt = {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onClearArt = { vm.clearArtistArt(artist.id) },
        onPlay = { vm.playAll(artist.songs) },
        onShuffle = { vm.playShuffled(artist.songs) },
        songs = artist.songs,
        currentSongId = playerState.currentSongId,
        vm = vm,
        onSongMenu = onSongMenu,
    )
}

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    vm: MainViewModel,
    onBack: () -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val playlists by vm.playlists.collectAsState()
    val library by vm.library.collectAsState()
    val playerState by vm.playerState.collectAsState()
    val playlist = playlists.firstOrNull { it.id == playlistId }

    if (playlist == null) {
        MissingScreen("Playlist not found", onBack)
        return
    }

    // Recomputed against `library` so songs removed from disk disappear from the playlist view.
    val songs = remember(playlist, library) { vm.songsOf(playlist) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.deletePlaylist(playlist.id); onBack() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete playlist")
                    }
                },
            )
        },
    ) { padding ->
        if (songs.isEmpty()) {
            EmptyState("This playlist is empty.", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                PlayButtons(
                    onPlay = { vm.playAll(songs) },
                    onShuffle = { vm.playShuffled(songs) },
                )
            }
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    art = vm.artForSong(song),
                    highlighted = song.id == playerState.currentSongId,
                    onClick = { vm.playAll(songs, index) },
                    onMenuClick = { onSongMenu(song) },
                )
            }
        }
    }
}

@Composable
fun GenreDetailScreen(
    genreId: Long,
    vm: MainViewModel,
    onBack: () -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val library by vm.library.collectAsState()
    val playerState by vm.playerState.collectAsState()
    val genre = library.genres.firstOrNull { it.id == genreId }

    if (genre == null) {
        MissingScreen("Genre not found", onBack)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(genre.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                PlayButtons(
                    onPlay = { vm.playAll(genre.songs) },
                    onShuffle = { vm.playShuffled(genre.songs) },
                )
            }
            itemsIndexed(genre.songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    art = vm.artForSong(song),
                    highlighted = song.id == playerState.currentSongId,
                    onClick = { vm.playAll(genre.songs, index) },
                    onMenuClick = { onSongMenu(song) },
                )
            }
        }
    }
}

@Composable
fun FolderDetailScreen(
    folderPath: String,
    vm: MainViewModel,
    onBack: () -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val library by vm.library.collectAsState()
    val playerState by vm.playerState.collectAsState()
    val songs = remember(folderPath, library) { vm.songsInFolder(folderPath) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            folderPath.substringAfterLast('/'),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            folderPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (songs.isEmpty()) {
            EmptyState("No songs in this folder.", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                PlayButtons(
                    onPlay = { vm.playAll(songs) },
                    onShuffle = { vm.playShuffled(songs) },
                )
            }
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    art = vm.artForSong(song),
                    highlighted = song.id == playerState.currentSongId,
                    onClick = { vm.playAll(songs, index) },
                    onMenuClick = { onSongMenu(song) },
                )
            }
        }
    }
}

/** Shared album/artist layout: big artwork with an edit affordance, then the track list. */
@Composable
private fun DetailScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    art: @Composable (Modifier) -> Unit,
    hasCustomArt: Boolean,
    onEditArt: () -> Unit,
    onClearArt: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    songs: List<Song>,
    currentSongId: Long?,
    vm: MainViewModel,
    onSongMenu: (Song) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    art(Modifier.size(200.dp))

                    Spacer(Modifier.height(12.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    ArtworkSourceToggle(
                        hasCustomArt = hasCustomArt,
                        onUseEmbedded = onClearArt,
                        onUseCustom = onEditArt,
                    )

                    PlayButtons(onPlay = onPlay, onShuffle = onShuffle)
                }
            }

            // Only label discs when there's more than one — a single-disc album needs no header.
            val multiDisc = songs.mapNotNull { it.disc.takeIf { d -> d > 0 } }.distinct().size > 1

            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                if (multiDisc && (index == 0 || songs[index - 1].disc != song.disc)) {
                    Text(
                        text = "Disc ${song.disc}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                SongRow(
                    song = song,
                    art = vm.artForSong(song),
                    highlighted = song.id == currentSongId,
                    onClick = { vm.playAll(songs, index) },
                    onMenuClick = { onSongMenu(song) },
                    trailing = song.track.takeIf { it > 0 }?.toString()
                        ?: formatDuration(song.durationMs),
                )
            }
        }
    }
}

/**
 * Chooses where this album's or artist's image comes from.
 *
 * Shown as an explicit labelled toggle rather than an icon floating over the artwork — an overlaid
 * icon disappears against a dark cover, which made the feature impossible to find.
 */
@Composable
private fun ArtworkSourceToggle(
    hasCustomArt: Boolean,
    onUseEmbedded: () -> Unit,
    onUseCustom: () -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(top = 12.dp)) {
        SegmentedButton(
            selected = !hasCustomArt,
            onClick = onUseEmbedded,
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) {
            Text("Embedded")
        }
        SegmentedButton(
            selected = hasCustomArt,
            // Tapping "Custom" always reopens the picker, so an already-custom image
            // can be swapped without first reverting to the embedded one.
            onClick = onUseCustom,
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) {
            Text(if (hasCustomArt) "Custom — change" else "Custom")
        }
    }
}

@Composable
private fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        Button(onClick = onPlay) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text("Play", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onShuffle) {
            Icon(Icons.Default.Shuffle, contentDescription = null)
            Text("Shuffle", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun MissingScreen(message: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        EmptyState(message, Modifier.padding(padding))
    }
}
