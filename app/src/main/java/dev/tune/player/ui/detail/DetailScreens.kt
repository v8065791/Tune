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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tune.player.data.NameCollator
import dev.tune.player.data.Album
import dev.tune.player.data.Song
import dev.tune.player.data.SortOrder
import dev.tune.player.data.comparator
import dev.tune.player.ui.MainViewModel
import dev.tune.player.ui.components.Artwork
import dev.tune.player.ui.components.EmptyState
import dev.tune.player.ui.components.ListCard
import dev.tune.player.ui.components.SelectableSongRow
import dev.tune.player.ui.components.SelectionTopBar
import dev.tune.player.ui.components.formatDuration

/**
 * Applies the detail-screen sort order to a track list.
 *
 * Deliberately a composable rather than a ViewModel call: reading `StateFlow.value` from a plain
 * function does not subscribe, so the list would only re-sort once something unrelated forced a
 * recomposition. That exact mistake has already caused two bugs in this app.
 */
@Composable
private fun sortedForDetail(vm: MainViewModel, songs: List<Song>): List<Song> {
    val order by vm.detailSort.collectAsStateWithLifecycle()
    val descending by vm.detailSortDescending.collectAsStateWithLifecycle()
    val ignoreArticles by vm.autoSortNames.collectAsStateWithLifecycle()
    val stats by vm.playStats.collectAsStateWithLifecycle()

    return remember(songs, order, descending, ignoreArticles, stats) {
        val comparator = order?.comparator(NameCollator(ignoreArticles), stats, descending)
        if (comparator == null) songs else songs.sortedWith(comparator)
    }
}

/**
 * The "Songs" header with a sort control, as it sits above every detail track list.
 *
 * "Default order" is the first option and the initial state, because each list arrives already
 * ordered in a way no [SortOrder] reproduces — an album by disc and track, a playlist by however
 * the user arranged it.
 */
@Composable
private fun SongsHeader(vm: MainViewModel, count: Int) {
    val order by vm.detailSort.collectAsStateWithLifecycle()
    val descending by vm.detailSortDescending.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Songs",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        // An active sort also hides disc headers and track numbers, which reads as missing data
        // unless something says why. The sort persists across screens, so it is easy to forget
        // one is on at all.
        if (order != null) {
            Text(
                text = "  ${order?.label}${if (descending) " ↓" else " ↑"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "Sort songs",
                    tint = if (order != null) MaterialTheme.colorScheme.primary
                    else LocalContentColor.current,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Default order") },
                    trailingIcon = {
                        if (order == null) Icon(Icons.Default.Check, contentDescription = null)
                    },
                    onClick = {
                        vm.setDetailSort(null)
                        menuOpen = false
                    },
                )
                SortOrder.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        trailingIcon = {
                            if (option == order) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            vm.setDetailSort(option)
                            menuOpen = false
                        },
                    )
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
                    // Direction has nothing to reverse while the list is in its default order.
                    enabled = order != null,
                    onClick = {
                        vm.setDetailSortDescending(!descending)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/**
 * The contextual selection bar as it sits atop a detail track list. [songsInView] is the list the
 * screen is showing, so "select all", play and queue act on exactly those tracks in their current
 * order. Only rendered while a selection is active.
 */
@Composable
private fun DetailSelectionBar(
    vm: MainViewModel,
    count: Int,
    songsInView: List<Song>,
    onSelectionToPlaylist: () -> Unit,
    onSelectionSetGenre: () -> Unit,
) {
    SelectionTopBar(
        count = count,
        onClear = { vm.clearSelection() },
        onSelectAll = { vm.selectAll(songsInView) },
        onPlay = { vm.playSelection(songsInView) },
        onQueue = { vm.queueSelection(songsInView) },
        onAddToPlaylist = onSelectionToPlaylist,
        onSetGenre = onSelectionSetGenre,
        onDelete = { vm.promptDeleteSelection(songsInView) },
    )
}

@Composable
fun AlbumDetailScreen(
    albumId: Long,
    vm: MainViewModel,
    onBack: () -> Unit,
    onSongMenu: (Song) -> Unit,
    onSelectionToPlaylist: () -> Unit,
    onSelectionSetGenre: () -> Unit,
) {
    // Re-read from the library flow so the screen follows rescans and folder changes.
    val library by vm.library.collectAsStateWithLifecycle()
    val overrides by vm.artworkOverrides.collectAsStateWithLifecycle()
    val album = library.albums.firstOrNull { it.id == albumId }
    val playerState by vm.playerState.collectAsStateWithLifecycle()

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
        onSelectionToPlaylist = onSelectionToPlaylist,
        onSelectionSetGenre = onSelectionSetGenre,
    )
}

@Composable
fun ArtistDetailScreen(
    artistId: Long,
    vm: MainViewModel,
    onBack: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    onSongMenu: (Song) -> Unit,
    onSelectionToPlaylist: () -> Unit,
    onSelectionSetGenre: () -> Unit,
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val overrides by vm.artworkOverrides.collectAsStateWithLifecycle()
    val artist = library.artists.firstOrNull { it.id == artistId }
    val playerState by vm.playerState.collectAsStateWithLifecycle()

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
        onSelectionToPlaylist = onSelectionToPlaylist,
        onSelectionSetGenre = onSelectionSetGenre,
        albumSections = listOf(
            "Albums" to artist.albums,
            "Appears on" to artist.appearsOn,
        ).filter { it.second.isNotEmpty() },
        onAlbumClick = onAlbumClick,
    )
}

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    vm: MainViewModel,
    onBack: () -> Unit,
    onSongMenu: (Song) -> Unit,
    onSelectionToPlaylist: () -> Unit,
    onSelectionSetGenre: () -> Unit,
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val selecting = selection.isNotEmpty()
    val playlist = playlists.firstOrNull { it.id == playlistId }

    if (playlist == null) {
        MissingScreen("Playlist not found", onBack)
        return
    }

    // Recomputed against `library` so songs removed from disk disappear from the playlist view.
    val songs = remember(playlist, library) { vm.songsOf(playlist) }
    val ordered = sortedForDetail(vm, songs)
    val exportPlaylist = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri -> uri?.let { vm.exportPlaylist(it, playlist) } }

    Scaffold(
        topBar = {
            if (selecting) DetailSelectionBar(
                vm, selection.size, ordered, onSelectionToPlaylist, onSelectionSetGenre,
            ) else TopAppBar(
                title = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { exportPlaylist.launch("${playlist.name}.m3u8") }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Export playlist")
                    }
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
                    onPlay = { vm.playAll(ordered) },
                    onShuffle = { vm.playShuffled(ordered) },
                )
            }
            item { SongsHeader(vm, ordered.size) }
            itemsIndexed(ordered, key = { _, song -> song.id }) { index, song ->
                SelectableSongRow(
                    song = song,
                    art = vm.artForSong(song),
                    highlighted = song.id == playerState.currentSongId,
                    selecting = selecting,
                    isSelected = song.id in selection,
                    onToggle = { vm.toggleSelection(song) },
                    onPlay = { vm.playAll(ordered, index) },
                    onMenu = { onSongMenu(song) },
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
    onSelectionToPlaylist: () -> Unit,
    onSelectionSetGenre: () -> Unit,
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val selecting = selection.isNotEmpty()
    val genre = library.genres.firstOrNull { it.id == genreId }

    if (genre == null) {
        MissingScreen("Genre not found", onBack)
        return
    }

    val ordered = sortedForDetail(vm, genre.songs)

    Scaffold(
        topBar = {
            if (selecting) DetailSelectionBar(
                vm, selection.size, ordered, onSelectionToPlaylist, onSelectionSetGenre,
            ) else TopAppBar(
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
                    onPlay = { vm.playAll(ordered) },
                    onShuffle = { vm.playShuffled(ordered) },
                )
            }
            item { SongsHeader(vm, ordered.size) }
            itemsIndexed(ordered, key = { _, song -> song.id }) { index, song ->
                SelectableSongRow(
                    song = song,
                    art = vm.artForSong(song),
                    highlighted = song.id == playerState.currentSongId,
                    selecting = selecting,
                    isSelected = song.id in selection,
                    onToggle = { vm.toggleSelection(song) },
                    onPlay = { vm.playAll(ordered, index) },
                    onMenu = { onSongMenu(song) },
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
    onSelectionToPlaylist: () -> Unit,
    onSelectionSetGenre: () -> Unit,
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val selecting = selection.isNotEmpty()
    val songs = remember(folderPath, library) { vm.songsInFolder(folderPath) }
    val ordered = sortedForDetail(vm, songs)

    Scaffold(
        topBar = {
            if (selecting) DetailSelectionBar(
                vm, selection.size, ordered, onSelectionToPlaylist, onSelectionSetGenre,
            ) else TopAppBar(
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
                    onPlay = { vm.playAll(ordered) },
                    onShuffle = { vm.playShuffled(ordered) },
                )
            }
            item { SongsHeader(vm, ordered.size) }
            itemsIndexed(ordered, key = { _, song -> song.id }) { index, song ->
                SelectableSongRow(
                    song = song,
                    art = vm.artForSong(song),
                    highlighted = song.id == playerState.currentSongId,
                    selecting = selecting,
                    isSelected = song.id in selection,
                    onToggle = { vm.toggleSelection(song) },
                    onPlay = { vm.playAll(ordered, index) },
                    onMenu = { onSongMenu(song) },
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
    onSelectionToPlaylist: () -> Unit,
    onSelectionSetGenre: () -> Unit,
    albumSections: List<Pair<String, List<Album>>> = emptyList(),
    onAlbumClick: (Album) -> Unit = {},
) {
    val ordered = sortedForDetail(vm, songs)
    val reordered = vm.detailSort.collectAsStateWithLifecycle().value != null
    val selection by vm.selection.collectAsStateWithLifecycle()
    val selecting = selection.isNotEmpty()

    Scaffold(
        topBar = {
            if (selecting) DetailSelectionBar(
                vm, selection.size, ordered, onSelectionToPlaylist, onSelectionSetGenre,
            ) else TopAppBar(
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

            albumSections.forEach { (label, albums) ->
                item {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(albums, key = { "$label-${it.id}" }) { album ->
                    ListCard(
                        art = vm.artForAlbum(album),
                        title = album.name,
                        subtitle = if (album.year > 0) "${album.artist} · ${album.year}" else album.artist,
                        rounded = false,
                        onClick = { onAlbumClick(album) },
                    )
                }
            }

            item { SongsHeader(vm, ordered.size) }

            // Disc headers only make sense while the list is in disc/track order. Once it is
            // sorted by, say, title, the tracks no longer arrive grouped by disc and the headers
            // would appear repeatedly and mean nothing.
            val multiDisc = !reordered &&
                songs.mapNotNull { it.disc.takeIf { d -> d > 0 } }.distinct().size > 1

            itemsIndexed(ordered, key = { _, song -> song.id }) { index, song ->
                if (multiDisc && (index == 0 || ordered[index - 1].disc != song.disc)) {
                    Text(
                        text = "Disc ${song.disc}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                SelectableSongRow(
                    song = song,
                    art = vm.artForSong(song),
                    highlighted = song.id == currentSongId,
                    selecting = selecting,
                    isSelected = song.id in selection,
                    onToggle = { vm.toggleSelection(song) },
                    onPlay = { vm.playAll(ordered, index) },
                    onMenu = { onSongMenu(song) },
                    trailing = song.track.takeIf { it > 0 && !reordered }?.toString()
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
            Text("Custom")
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
