package dev.tune.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tune.player.data.Song
import dev.tune.player.ui.components.EmptyState
import dev.tune.player.ui.components.SongRow
import dev.tune.player.ui.components.formatDuration

/**
 * Tracks that appear more than once in the library, grouped by what they look like.
 *
 * This reports only. Deleting or moving files is out of scope, so each copy shows its folder and
 * size and the user decides what to do outside the app.
 */
@Composable
fun DuplicatesScreen(vm: MainViewModel, onBack: () -> Unit, onSongMenu: (Song) -> Unit) {
    val groups by vm.duplicates.collectAsState()
    val playerState by vm.playerState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duplicates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (groups.isEmpty()) {
            EmptyState(
                "No duplicates found.\nTracks are matched on title and artist.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                Text(
                    text = "${groups.size} tracks appear more than once. Nothing here changes " +
                        "your files — this is a report.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }

            groups.forEach { group ->
                item(key = "header-${group.title}-${group.artist}") {
                    Column {
                        HorizontalDivider()
                        Text(
                            text = "${group.title} — ${group.artist}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(
                                start = 20.dp,
                                top = 16.dp,
                                bottom = 4.dp,
                                end = 20.dp,
                            ),
                        )
                        Text(
                            text = "${group.songs.size} copies",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                        )
                    }
                }
                items(group.songs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        art = vm.artForSong(song),
                        highlighted = song.id == playerState.currentSongId,
                        onClick = { vm.playAll(listOf(song)) },
                        onMenuClick = { onSongMenu(song) },
                        // Duration and size are what tell two copies apart; the artist and album
                        // shown by default are identical by definition here.
                        trailing = "${formatDuration(song.durationMs)} · ${song.sizeBytes.asMb()}",
                    )
                    Text(
                        text = song.folderPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 72.dp, bottom = 8.dp, end = 20.dp),
                    )
                }
            }
        }
    }
}

private fun Long.asMb(): String = "%.1f MB".format(this / 1_048_576.0)
