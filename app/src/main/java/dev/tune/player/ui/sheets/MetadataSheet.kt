package dev.tune.player.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tune.player.art.ArtRequest
import dev.tune.player.data.PackedDate
import dev.tune.player.data.Song
import dev.tune.player.ui.components.Artwork
import dev.tune.player.ui.components.formatDuration
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Full tag readout for one song. Fields that a file doesn't carry are simply omitted rather than
 * shown as "unknown", so the sheet reflects what is actually tagged.
 */
@Composable
fun MetadataSheet(song: Song, art: ArtRequest, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(art = art, modifier = Modifier.size(80.dp))
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            MetadataRow("Title", song.title)
            MetadataRow("Artist", song.artist)
            MetadataRow("Album", song.album)
            MetadataRow("Album artist", song.albumArtist)
            MetadataRow("Composer", song.composer)
            MetadataRow("Genre", song.genre)
            // Shows the full tagged date when there is one, so it's visible why two songs from
            // the same year sort the way they do.
            MetadataRow(
                "Release date",
                PackedDate.format(song.releaseDate) ?: song.year.takeIf { it > 0 }?.toString(),
            )
            MetadataRow("Track", song.track.takeIf { it > 0 }?.toString())
            MetadataRow("Disc", song.disc.takeIf { it > 0 }?.toString())
            MetadataRow("Duration", formatDuration(song.durationMs))

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            MetadataRow("Format", song.mimeType)
            MetadataRow("Size", formatSize(song.sizeBytes))
            MetadataRow("Added", formatDate(song.dateAddedSeconds))
            MetadataRow("Modified", formatDate(song.dateModifiedSeconds))
            MetadataRow("Folder", song.folderPath)
            MetadataRow("File", song.path.substringAfterLast('/'))
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatSize(bytes: Long): String? {
    if (bytes <= 0) return null
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb)
    else String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}

private fun formatDate(epochSeconds: Long): String? {
    if (epochSeconds <= 0) return null
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))
}
