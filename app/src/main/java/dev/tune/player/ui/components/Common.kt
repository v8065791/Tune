package dev.tune.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.tune.player.art.ArtRequest
import dev.tune.player.data.Song

/**
 * Artwork presentation settings, passed ambiently so every call site doesn't have to thread them
 * through. Defaults match a fresh install.
 */
data class ArtworkStyle(
    val showCovers: Boolean = true,
    val squareCovers: Boolean = true,
    val roundedCorners: Boolean = true,
)

val LocalArtworkStyle = staticCompositionLocalOf { ArtworkStyle() }

/**
 * Album/artist artwork with a music-note placeholder for anything untagged.
 * [rounded] gives artists circular images without a second composable.
 */
@Composable
fun Artwork(
    art: ArtRequest,
    modifier: Modifier = Modifier,
    rounded: Boolean = false,
    cornerRadius: Int = 8,
) {
    val style = LocalArtworkStyle.current
    val shape = when {
        rounded -> RoundedCornerShape(percent = 50)
        style.roundedCorners -> RoundedCornerShape(cornerRadius.dp)
        else -> RoundedCornerShape(0.dp)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxSize().padding(12.dp),
        )
        if (!art.isEmpty && style.showCovers) {
            AsyncImage(
                model = art,
                contentDescription = null,
                // Square crops fill the frame; otherwise the whole cover is kept visible.
                contentScale = if (style.squareCovers) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A single row in any song list. [highlighted] marks the currently playing track. */
@Composable
fun SongRow(
    song: Song,
    art: ArtRequest,
    highlighted: Boolean,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String = formatDuration(song.durationMs),
    /** Non-null puts the row in selection mode, replacing artwork with a checkbox. */
    selected: Boolean? = null,
    /** Long-press action. Defaults to the overflow menu when no bulk selection is offered. */
    onLongClick: () -> Unit = onMenuClick,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                if (selected == true) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.size(48.dp))
        } else {
            Artwork(art = art, modifier = Modifier.size(48.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                color = if (highlighted) MaterialTheme.colorScheme.primary else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${song.artist} · ${song.album}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = trailing,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        IconButton(onClick = onMenuClick) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options for ${song.title}")
        }
    }
}

/**
 * A compact row for any group — album, artist, genre, playlist, folder — used when the tab is in
 * list mode. [GridCard] is the same content laid out as a card.
 */
@Composable
fun ListCard(
    art: ArtRequest,
    title: String,
    subtitle: String,
    rounded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art = art, rounded = rounded, modifier = Modifier.size(48.dp))
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Square artwork card used by the album and artist grids. */
@Composable
fun GridCard(
    art: ArtRequest,
    title: String,
    subtitle: String,
    rounded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** Non-null puts the card in selection mode, overlaying a tick. */
    selected: Boolean? = null,
) {
    Column(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                if (selected == true) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Artwork(
                art = art,
                rounded = rounded,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            if (selected != null) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

/** mm:ss, or h:mm:ss once a track runs past an hour. */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
