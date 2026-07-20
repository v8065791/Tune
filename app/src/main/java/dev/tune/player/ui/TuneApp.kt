package dev.tune.player.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.tune.player.data.Song
import dev.tune.player.ui.detail.AlbumDetailScreen
import dev.tune.player.ui.detail.ArtistDetailScreen
import dev.tune.player.ui.detail.FolderDetailScreen
import dev.tune.player.ui.detail.GenreDetailScreen
import dev.tune.player.ui.detail.PlaylistDetailScreen
import dev.tune.player.ui.sheets.MetadataSheet
import dev.tune.player.ui.sheets.PlaylistPickerSheet
import dev.tune.player.ui.sheets.SongActionsSheet

private object Routes {
    const val HOME = "home"
    const val NOW_PLAYING = "now_playing"
    const val SETTINGS = "settings"
    const val SETTINGS_LIBRARY = "settings/library"
    const val SETTINGS_PLAYBACK = "settings/playback"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val DUPLICATES = "duplicates"
    const val SEARCH = "search"
    const val FOLDER_SETTINGS = "folder_settings"
    const val HOME_TABS = "home_tabs"
    const val QUEUE = "queue"
    const val ALBUM = "album/{albumId}"
    const val ARTIST = "artist/{artistId}"
    const val GENRE = "genre/{genreId}"
    const val PLAYLIST = "playlist/{playlistId}"
    const val FOLDER = "folder/{folderPath}"

    fun album(id: Long) = "album/$id"
    fun artist(id: Long) = "artist/$id"
    fun genre(id: Long) = "genre/$id"
    fun playlist(id: String) = "playlist/$id"
    // Paths contain slashes, so they must be encoded to survive the route.
    fun folder(path: String) = "folder/${Uri.encode(path)}"
}

@Composable
fun TuneApp(vm: MainViewModel) {
    val navController = rememberNavController()
    val playerState by vm.playerState.collectAsState()

    // Surface failures (e.g. an unreadable picked image) instead of leaving the user guessing.
    val context = LocalContext.current
    val message by vm.messages.collectAsState()
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearMessage()
        }
    }

    val playlists by vm.playlists.collectAsState()
    val metadataSong by vm.metadataSong.collectAsState()

    // Sheet + dialog state, hoisted here so any screen can trigger them.
    var actionsSong by remember { mutableStateOf<Song?>(null) }
    var playlistPickerFor by remember { mutableStateOf<Song?>(null) }
    var newPlaylistForSong by remember { mutableStateOf<Song?>(null) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var playlistPickerForSelection by remember { mutableStateOf(false) }
    val library by vm.library.collectAsState()

    // The mini player lives above the NavHost rather than inside any one screen, so it stays put
    // while navigating. The now playing screen is the exception — it already shows the controls.
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    // Derive from playerState here, in this composable's own scope. Calling vm.currentSong()
    // reads StateFlow.value without subscribing, so the bar only appeared once something else
    // forced a recomposition — such as navigating to another screen.
    val currentSong = remember(playerState.currentSongId, library) {
        library.songs.firstOrNull { it.id == playerState.currentSongId }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.weight(1f),
        ) {
        composable(Routes.HOME) {
            HomeScreen(
                vm = vm,
                onAlbumClick = { navController.navigate(Routes.album(it.id)) },
                onArtistClick = { navController.navigate(Routes.artist(it.id)) },
                onGenreClick = { navController.navigate(Routes.genre(it.id)) },
                onPlaylistClick = { navController.navigate(Routes.playlist(it.id)) },
                onFolderClick = { navController.navigate(Routes.folder(it.path)) },
                onSongMenu = { actionsSong = it },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onCreatePlaylist = { showNewPlaylistDialog = true },
                onExpandPlayer = { navController.navigate(Routes.NOW_PLAYING) },
                onSelectionToPlaylist = { playlistPickerForSelection = true },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenLibrary = { navController.navigate(Routes.SETTINGS_LIBRARY) },
                onOpenPlayback = { navController.navigate(Routes.SETTINGS_PLAYBACK) },
                onOpenAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
            )
        }

        composable(Routes.SETTINGS_LIBRARY) {
            LibrarySettingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenFolders = { navController.navigate(Routes.FOLDER_SETTINGS) },
                onOpenDuplicates = { navController.navigate(Routes.DUPLICATES) },
            )
        }

        composable(Routes.SETTINGS_PLAYBACK) {
            PlaybackSettingsScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS_APPEARANCE) {
            AppearanceSettingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenHomeTabs = { navController.navigate(Routes.HOME_TABS) },
            )
        }

        composable(Routes.DUPLICATES) {
            DuplicatesScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onSongMenu = { actionsSong = it },
            )
        }

        composable(Routes.QUEUE) {
            QueueScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.HOME_TABS) {
            HomeTabsScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onAlbumClick = { navController.navigate(Routes.album(it.id)) },
                onArtistClick = { navController.navigate(Routes.artist(it.id)) },
                onGenreClick = { navController.navigate(Routes.genre(it.id)) },
                onSongMenu = { actionsSong = it },
            )
        }

        composable(
            Routes.GENRE,
            arguments = listOf(navArgument("genreId") { type = NavType.LongType }),
        ) { entry ->
            GenreDetailScreen(
                genreId = entry.arguments?.getLong("genreId") ?: 0L,
                vm = vm,
                onBack = { navController.popBackStack() },
                onSongMenu = { actionsSong = it },
            )
        }

        composable(Routes.NOW_PLAYING) {
            val song = vm.currentSong()
            if (song == null) navController.popBackStack()
            else NowPlayingScreen(
                song = song,
                vm = vm,
                state = playerState,
                onBack = { navController.popBackStack() },
                onOpenQueue = { navController.navigate(Routes.QUEUE) },
                onAddToPlaylist = { playlistPickerFor = song },
            )
        }

        composable(Routes.FOLDER_SETTINGS) {
            FolderPickerScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        composable(
            Routes.ALBUM,
            arguments = listOf(navArgument("albumId") { type = NavType.LongType }),
        ) { entry ->
            AlbumDetailScreen(
                albumId = entry.arguments?.getLong("albumId") ?: 0L,
                vm = vm,
                onBack = { navController.popBackStack() },
                onSongMenu = { actionsSong = it },
            )
        }

        composable(
            Routes.ARTIST,
            arguments = listOf(navArgument("artistId") { type = NavType.LongType }),
        ) { entry ->
            ArtistDetailScreen(
                artistId = entry.arguments?.getLong("artistId") ?: 0L,
                vm = vm,
                onBack = { navController.popBackStack() },
                onSongMenu = { actionsSong = it },
            )
        }

        composable(
            Routes.PLAYLIST,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
        ) { entry ->
            PlaylistDetailScreen(
                playlistId = entry.arguments?.getString("playlistId").orEmpty(),
                vm = vm,
                onBack = { navController.popBackStack() },
                onSongMenu = { actionsSong = it },
            )
        }

        composable(
            Routes.FOLDER,
            arguments = listOf(navArgument("folderPath") { type = NavType.StringType }),
        ) { entry ->
            FolderDetailScreen(
                folderPath = Uri.decode(entry.arguments?.getString("folderPath").orEmpty()),
                vm = vm,
                onBack = { navController.popBackStack() },
                onSongMenu = { actionsSong = it },
            )
        }
        }

        if (currentSong != null && currentRoute != Routes.NOW_PLAYING) {
            MiniPlayer(
                song = currentSong,
                vm = vm,
                state = playerState,
                onExpand = { navController.navigate(Routes.NOW_PLAYING) },
            )
        }
    }

    // ---- Overlays ----------------------------------------------------------

    actionsSong?.let { song ->
        SongActionsSheet(
            song = song,
            art = vm.artForSong(song),
            onDismiss = { actionsSong = null },
            onPlay = { vm.playAll(listOf(song)) },
            onAddToQueue = { vm.player.addToQueue(listOf(song)) },
            onAddToPlaylist = {
                playlistPickerFor = song
                actionsSong = null
            },
            onGoToAlbum = { navController.navigate(Routes.album(song.albumId)) },
            onGoToArtist = { navController.navigate(Routes.artist(song.artistId)) },
            onShowMetadata = {
                vm.showMetadata(song)
                actionsSong = null
            },
        )
    }

    playlistPickerFor?.let { song ->
        PlaylistPickerSheet(
            playlists = playlists,
            onDismiss = { playlistPickerFor = null },
            onPick = { playlistId ->
                vm.addToPlaylist(playlistId, listOf(song.id))
                playlistPickerFor = null
            },
            onCreateNew = {
                newPlaylistForSong = song
                playlistPickerFor = null
            },
        )
    }

    if (playlistPickerForSelection) {
        PlaylistPickerSheet(
            playlists = playlists,
            onDismiss = { playlistPickerForSelection = false },
            onPick = { playlistId ->
                vm.addSelectionToPlaylist(playlistId, library.songs)
                playlistPickerForSelection = false
            },
            onCreateNew = {
                // Creating from a selection isn't wired up yet; fall back to an empty playlist.
                playlistPickerForSelection = false
                showNewPlaylistDialog = true
            },
        )
    }

    metadataSong?.let { song ->
        MetadataSheet(
            song = song,
            art = vm.artForSong(song),
            onDismiss = { vm.dismissMetadata() },
        )
    }

    if (showNewPlaylistDialog || newPlaylistForSong != null) {
        val seedSong = newPlaylistForSong
        NamePlaylistDialog(
            onDismiss = {
                showNewPlaylistDialog = false
                newPlaylistForSong = null
            },
            onConfirm = { name ->
                vm.createPlaylist(name, listOfNotNull(seedSong?.id))
                showNewPlaylistDialog = false
                newPlaylistForSong = null
            },
        )
    }
}

@Composable
private fun NamePlaylistDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
