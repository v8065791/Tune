package dev.tune.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
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
import androidx.media3.common.Player
import dev.tune.player.data.Song
import dev.tune.player.player.PlayerState
import dev.tune.player.ui.components.Artwork
import dev.tune.player.ui.components.formatDuration

/** Persistent bar above the tabs showing whatever is playing. */
@Composable
fun MiniPlayer(
    song: Song,
    vm: MainViewModel,
    state: PlayerState,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progressState by vm.playbackProgress.collectAsStateWithLifecycle()
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // The app draws edge to edge, so pad past the gesture/navigation bar — without this
            // the controls sit underneath it and can't be tapped.
            .navigationBarsPadding()
    ) {
        // Thin progress line — cheaper to read at a glance than a full slider.
        val progress = if (progressState.durationMs > 0) {
            (progressState.positionMs.toFloat() / progressState.durationMs).coerceIn(0f, 1f)
        } else 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(art = vm.artForSong(song), modifier = Modifier.size(56.dp))
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            ) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { vm.player.previous() }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(onClick = { vm.player.togglePlayPause() }, modifier = Modifier.size(52.dp)) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(onClick = { vm.player.next() }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next")
            }
        }
    }
}

@Composable
fun NowPlayingScreen(
    song: Song,
    vm: MainViewModel,
    state: PlayerState,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    // While the user drags, show their position rather than the player's, or the thumb fights back.
    var scrubPosition by remember { mutableStateOf<Float?>(null) }
    var showSleepTimer by remember { mutableStateOf(false) }
    val progress by vm.playbackProgress.collectAsStateWithLifecycle()
    val sleepRemaining by vm.sleepRemainingMs.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.weight(1f))
            val favourites by vm.favourites.collectAsStateWithLifecycle()
            val isFavourite = song.id in favourites
            IconButton(onClick = { vm.toggleFavourite(song) }) {
                Icon(
                    if (isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavourite) "Remove from favourites"
                    else "Add to favourites",
                    tint = if (isFavourite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = "Add to playlist",
                )
            }
            IconButton(onClick = onOpenQueue) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Queue (${state.queueSize} tracks)",
                )
            }
            IconButton(onClick = { showSleepTimer = true }) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = "Sleep timer",
                    tint = if (sleepRemaining != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.showMetadata(song) }) {
                Icon(Icons.Default.Info, contentDescription = "Track metadata")
            }
        }

        Spacer(Modifier.height(16.dp))

        Artwork(
            art = vm.artForSong(song),
            cornerRadius = 16,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )

        Spacer(Modifier.height(32.dp))

        Text(
            song.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "${song.artist} · ${song.album}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(24.dp))

        val duration = progress.durationMs.takeIf { it > 0 } ?: song.durationMs
        val position = scrubPosition ?: progress.positionMs.toFloat()
        Slider(
            value = position.coerceIn(0f, duration.toFloat()),
            onValueChange = { scrubPosition = it },
            onValueChangeFinished = {
                scrubPosition?.let { vm.player.seekTo(it.toLong()) }
                scrubPosition = null
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                formatDuration(position.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.player.toggleShuffle() }) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (state.shuffle) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.player.previous() }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
            }
            IconButton(
                onClick = { vm.player.togglePlayPause() },
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
            }
            IconButton(onClick = { vm.player.next() }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { vm.player.cycleRepeatMode() }) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne
                    else Icons.Default.Repeat,
                    contentDescription = "Repeat mode",
                    tint = if (state.repeatMode == Player.REPEAT_MODE_OFF)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }

        SpeedControl(speed = state.speed, onSelect = vm::setSpeed)
    }

    if (showSleepTimer) {
        AlertDialog(
            onDismissRequest = { showSleepTimer = false },
            title = { Text("Sleep timer") },
            text = {
                Column {
                    sleepRemaining?.let {
                        Text("Pausing in ${(it / 60_000L).coerceAtLeast(1L)} minutes")
                    }
                    listOf(15, 30, 45, 60, 90).forEach { minutes ->
                        TextButton(
                            onClick = {
                                vm.setSleepTimer(minutes)
                                showSleepTimer = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("$minutes minutes") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepTimer = false }) { Text("Close") }
            },
            dismissButton = sleepRemaining?.let {
                {
                    TextButton(
                        onClick = {
                            vm.setSleepTimer(null)
                            showSleepTimer = false
                        }
                    ) { Text("Cancel timer") }
                }
            },
        )
    }
}

/** Playback rate presets. Pitch is preserved, so speech stays intelligible when sped up. */
@Composable
private fun SpeedControl(speed: Float, onSelect: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Speed",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { preset ->
            val selected = kotlin.math.abs(speed - preset) < 0.01f
            TextButton(onClick = { onSelect(preset) }, contentPadding = PaddingValues(4.dp)) {
                Text(
                    text = if (preset == 1f) "1x" else "${preset}x".replace(".0x", "x"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
