package dev.tune.player.ui

import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tune.player.data.AccentColour
import dev.tune.player.data.CoverMode
import dev.tune.player.data.PlayInMode
import dev.tune.player.data.ReplayGainMode
import dev.tune.player.data.ThemeMode

/**
 * Settings, split into categories that each open their own screen.
 *
 * Preference writes go straight to DataStore, so every control below reflects persisted state
 * rather than local UI state and there is nothing to "save".
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenAppearance: () -> Unit,
) {
    SettingsScaffold(title = "Settings", onBack = onBack) {
        item {
            CategoryRow(
                icon = Icons.Default.LibraryMusic,
                title = "Library",
                subtitle = "Folders, scanning, tags, duplicates",
                onClick = onOpenLibrary,
            )
        }
        item {
            CategoryRow(
                icon = Icons.Default.PlayCircle,
                title = "Playback",
                subtitle = "Queueing, resume, volume, equalizer",
                onClick = onOpenPlayback,
            )
        }
        item {
            CategoryRow(
                icon = Icons.Default.Palette,
                title = "Appearance",
                subtitle = "Theme, colours, tabs, artwork",
                onClick = onOpenAppearance,
            )
        }
    }
}

@Composable
fun LibrarySettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenDuplicates: () -> Unit,
) {
    SettingsScaffold(title = "Library", onBack = onBack) {
        item {
            ActionRow("Music folders", "Choose which folders are scanned", onOpenFolders)
        }
        item {
            val duplicates by vm.duplicates.collectAsState()
            ActionRow(
                title = "Find duplicates",
                subtitle = if (duplicates.isEmpty()) "No duplicate tracks found"
                else "${duplicates.size} tracks appear more than once",
                onClick = onOpenDuplicates,
            )
        }
        item {
            val observing by vm.observing.collectAsState()
            SwitchRow(
                title = "Watch for changes",
                subtitle = "Rescan automatically when the system media library changes",
                checked = observing,
            ) { value -> vm.edit { setObserving(value) } }
        }
        item {
            val autoSort by vm.autoSortNames.collectAsState()
            SwitchRow(
                title = "Ignore leading articles",
                subtitle = "Sort \"The Beatles\" under B",
                checked = autoSort,
            ) { value -> vm.edit { setAutoSortNames(value) } }
        }
        item {
            val hide by vm.hideCollaborators.collectAsState()
            SwitchRow(
                title = "Separate collaborations",
                subtitle = "List albums an artist only appears on apart from their own",
                checked = hide,
            ) { value -> vm.edit { setHideCollaborators(value) } }
        }
        item {
            val separators by vm.separators.collectAsState()
            SeparatorsRow(separators) { value -> vm.edit { setSeparators(value) } }
        }
    }
}

@Composable
fun PlaybackSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    SettingsScaffold(title = "Playback", onBack = onBack) {
        item {
            val mode by vm.playInListWith.collectAsState()
            ChoiceRow(
                title = "Tapping a song plays",
                current = mode,
                options = PlayInMode.entries,
                label = { it.label },
            ) { value -> vm.edit { setPlayInListWith(value) } }
        }
        item {
            val rewind by vm.rewindOnPrevious.collectAsState()
            SwitchRow(
                title = "Previous restarts the track",
                subtitle = "Skip back only when near the start",
                checked = rewind,
            ) { value -> vm.edit { setRewindOnPrevious(value) } }
        }
        item {
            val autoplay by vm.headsetAutoplay.collectAsState()
            SwitchRow(
                title = "Play on headset connect",
                subtitle = "Resume when headphones are plugged in",
                checked = autoplay,
            ) { value -> vm.edit { setHeadsetAutoplay(value) } }
        }
        item {
            val remember by vm.rememberPlayback.collectAsState()
            SwitchRow(
                title = "Remember playback",
                subtitle = "Restore the queue and position on next launch",
                checked = remember,
            ) { value -> vm.edit { setRememberPlayback(value) } }
        }
        item {
            val keep by vm.keepShuffle.collectAsState()
            SwitchRow(
                title = "Remember shuffle",
                subtitle = "Keep shuffle enabled between sessions",
                checked = keep,
            ) { value -> vm.edit { setKeepShuffle(value) } }
        }
        item {
            val gain by vm.replayGain.collectAsState()
            ChoiceRow(
                title = "ReplayGain",
                current = gain,
                options = ReplayGainMode.entries,
                label = { it.label },
            ) { value -> vm.edit { setReplayGain(value) } }
        }
        item {
            Text(
                text = "Reads the gain tag written by tools like foobar2000 or rsgain, in MP3, " +
                    "FLAC, Ogg Vorbis and Opus. Opus R128 tags are converted to the same scale. " +
                    "Quiet tracks are only boosted as far as the peak tag says is safe; without " +
                    "one they are left alone rather than risking clipping.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        item {
            val context = LocalContext.current
            ActionRow(
                title = "Equalizer",
                subtitle = "Open the system or a third-party equalizer",
            ) {
                // Android has no in-app EQ to call; the convention is to hand the audio session to
                // whichever equalizer the user has installed.
                val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                } else {
                    vm.reportMessage("No equalizer app is installed")
                }
            }
        }
    }
}

@Composable
fun AppearanceSettingsScreen(vm: MainViewModel, onBack: () -> Unit, onOpenHomeTabs: () -> Unit) {
    SettingsScaffold(title = "Appearance", onBack = onBack) {
        item {
            val tabs by vm.homeTabs.collectAsState()
            ActionRow(
                title = "Home tabs",
                subtitle = tabs.joinToString(", ") { it.label },
                onClick = onOpenHomeTabs,
            )
        }
        item {
            val grid by vm.gridView.collectAsState()
            SwitchRow(
                title = "Grid layout",
                subtitle = "Show tabs as cards instead of rows. The toolbar toggles this too.",
                checked = grid,
            ) { value -> vm.setGridView(value) }
        }
        item {
            val theme by vm.themeMode.collectAsState()
            ChoiceRow(
                title = "Theme",
                current = theme,
                options = ThemeMode.entries,
                label = { it.label },
            ) { value -> vm.edit { setThemeMode(value) } }
        }
        item {
            val black by vm.blackTheme.collectAsState()
            SwitchRow(
                title = "Black dark theme",
                subtitle = "True black backgrounds for OLED screens",
                checked = black,
            ) { value -> vm.edit { setBlackTheme(value) } }
        }
        item {
            val accent by vm.accent.collectAsState()
            val dynamicOn by vm.dynamicColor.collectAsState()
            ChoiceRow(
                title = "Accent colour",
                // Material You supplies its own accent, so this only applies when it's off.
                current = accent,
                options = AccentColour.entries,
                label = { if (dynamicOn) "${it.label} (Material You is on)" else it.label },
            ) { value -> vm.edit { setAccent(value) } }
        }
        item {
            val dynamic by vm.dynamicColor.collectAsState()
            SwitchRow(
                title = "Material You colours",
                subtitle = "Take the accent colour from your wallpaper",
                checked = dynamic,
            ) { value -> vm.edit { setDynamicColor(value) } }
        }
        item {
            val cover by vm.coverMode.collectAsState()
            ChoiceRow(
                title = "Cover art source",
                current = cover,
                options = CoverMode.entries,
                label = { it.label },
            ) { value -> vm.edit { setCoverMode(value) } }
        }
        item {
            val square by vm.squareCovers.collectAsState()
            SwitchRow(
                title = "Square covers",
                subtitle = "Crop artwork to a square instead of keeping its aspect ratio",
                checked = square,
            ) { value -> vm.edit { setSquareCovers(value) } }
        }
        item {
            val rounded by vm.roundedCorners.collectAsState()
            SwitchRow(
                title = "Rounded corners",
                subtitle = "Round the corners of artwork",
                checked = rounded,
            ) { value -> vm.edit { setRoundedCorners(value) } }
        }
    }
}

// ---- Shared building blocks ------------------------------------------------

/** The frame every settings screen shares: a titled bar with back, over a scrolling list. */
@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            content = content,
        )
    }
}

/** A top-level entry on the settings root, opening a sub-screen. */
@Composable
private fun CategoryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 20.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A setting with a small fixed set of values, shown as a radio dialog. */
@Composable
private fun <T> ChoiceRow(
    title: String,
    current: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    ActionRow(title, label(current)) { open = true }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(option)
                                    open = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == current, onClick = null)
                            Text(
                                label(option),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SeparatorsRow(current: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var draft by remember(current) { mutableStateOf(current) }

    ActionRow(
        title = "Multi-value separators",
        subtitle = current.ifBlank { "Off — tags are taken literally" },
    ) { open = true }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Multi-value separators") },
            text = {
                Column {
                    Text(
                        "Characters that split one tag into several, so \"A;B\" becomes two " +
                            "artists. Leave empty if your names legitimately contain these.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        label = { Text("e.g. ;/&") },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onChange(draft.trim())
                        open = false
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}
