package dev.tune.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tune.player.ui.components.Artwork
import dev.tune.player.ui.components.EmptyState
import dev.tune.player.ui.components.formatDuration

/**
 * The play queue, with reordering and removal.
 *
 * Reordering uses up/down buttons rather than drag-and-drop: Compose has no built-in reorderable
 * list, and hand-rolling drag gestures inside a LazyColumn is a large amount of fragile code for
 * a screen that is mostly used to bump one track.
 */
@Composable
fun QueueScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.playerState.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()

    // Resolve ids per position — the same song can legitimately appear twice in a queue.
    val byId = remember(library.songs) { library.songs.associateBy { it.id } }
    val entries = remember(state.queueIds, byId) { state.queueIds.map { byId[it] } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Queue") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState("Nothing queued.", Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            itemsIndexed(entries, key = { index, song -> "${song?.id ?: "missing"}-$index" }) { index, song ->
                val playing = index == state.queueIndex

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.player.playQueueItem(index) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (song != null) {
                        Artwork(art = vm.artForSong(song), modifier = Modifier.size(44.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            // A queued song can outlive its file, e.g. after a folder is excluded.
                            text = song?.title ?: "Unavailable",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                            color = if (playing) MaterialTheme.colorScheme.primary
                            else Color.Unspecified,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song?.let { "${it.artist} · ${formatDuration(it.durationMs)}" }
                                ?: "No longer in the library",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    IconButton(
                        onClick = { vm.player.moveQueueItem(index, index - 1) },
                        enabled = index > 0,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                    }
                    IconButton(
                        onClick = { vm.player.moveQueueItem(index, index + 1) },
                        enabled = index < entries.lastIndex,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                    }
                    IconButton(
                        onClick = { vm.player.removeQueueItem(index) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove from queue")
                    }
                }
            }
        }
    }
}
