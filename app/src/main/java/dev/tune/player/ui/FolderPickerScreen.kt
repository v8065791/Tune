package dev.tune.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tune.player.ui.components.EmptyState

/**
 * Which folders the library is built from. Everything is included by default; unchecking a folder
 * hides it and its subfolders without touching any files.
 */
@Composable
fun FolderPickerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val folders by vm.allFolders.collectAsState()
    val excluded by vm.excludedFolders.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Music folders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.includeAllFolders() }) { Text("Select all") }
                },
            )
        },
    ) { padding ->
        if (folders.isEmpty()) {
            EmptyState("No music folders found on this device.", Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    text = "Unchecked folders are hidden from every tab. Files are never modified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(folders, key = { it.path }) { folder ->
                // A folder counts as off if it or any ancestor was unchecked.
                val isExcluded = excluded.any {
                    folder.path == it || folder.path.startsWith("$it/")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.setFolderExcluded(folder, !isExcluded) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = !isExcluded,
                        onCheckedChange = { checked -> vm.setFolderExcluded(folder, !checked) },
                    )
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(
                            text = folder.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "${folder.songCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
