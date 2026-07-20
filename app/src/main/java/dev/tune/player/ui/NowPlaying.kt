package dev.tune.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        // Thin progress line — cheaper to read at a glance than a full slider.
        val progress = if (state.durationMs > 0) {
            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(art = vm.artForSong(song), modifier = Modifier.size(44.dp))
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            ) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { vm.player.togglePlayPause() }) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = { vm.player.next() }) {
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
) {
    // While the user drags, show their position rather than the player's, or the thumb fights back.
    var scrubPosition by remember { mutableStateOf<Float?>(null) }

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

        val duration = state.durationMs.takeIf { it > 0 } ?: song.durationMs
        val position = scrubPosition ?: state.positionMs.toFloat()
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
    }
}
