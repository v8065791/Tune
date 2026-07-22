package dev.tune.player.ui

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tune.player.data.FolderMode

/**
 * Chooses which directories the library is built from, in the style of Auxio's music folders.
 *
 * Directories are picked through the system file picker and always cover their subfolders, so one
 * entry can stand for a whole tree. The mode decides whether the list is a deny-list or an
 * allow-list; an empty list always means "scan everything".
 */
@Composable
fun FolderPickerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val folders by vm.excludedFolders.collectAsStateWithLifecycle()
    val mode by vm.folderMode.collectAsStateWithLifecycle()
    val allFolders by vm.allFolders.collectAsStateWithLifecycle()

    val pickDirectory = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { tree ->
            documentTreeToPath(tree)?.let { path -> vm.addFolder(path) }
                ?: vm.reportMessage("That location can't be mapped to a folder path")
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Music folders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickDirectory.launch(null) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add folder") },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        FolderMode.entries.forEachIndexed { index, entry ->
                            SegmentedButton(
                                selected = mode == entry,
                                onClick = { vm.setFolderMode(entry) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = FolderMode.entries.size,
                                ),
                            ) {
                                Text(entry.label)
                            }
                        }
                    }
                    Text(
                        text = when {
                            folders.isEmpty() -> "No folders chosen — the whole device is scanned."
                            mode == FolderMode.INCLUDE ->
                                "Only these folders and their subfolders are scanned."
                            else -> "These folders and their subfolders are skipped."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                HorizontalDivider()
            }

            items(folders.toList().sorted(), key = { it }) { path ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(
                            path.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                        )
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { vm.removeFolder(path) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove $path")
                    }
                }
            }

            // Discovered folders make it easy to add one without going through the file picker.
            item { SuggestionHeader() }
            items(allFolders.map { it.path }.filterNot { it in folders }, key = { "s-$it" }) { path ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.addFolder(path) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        path,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionHeader() {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            text = "Folders containing music",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

/**
 * Maps a SAF tree URI onto a filesystem path, since the library index is keyed by real paths.
 *
 * Only works for volumes reachable as plain files — "primary" (internal storage) and physical SD
 * cards. Cloud providers have no filesystem path and return null.
 */
private fun documentTreeToPath(tree: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
    val (volume, relative) = docId.split(':', limit = 2).let {
        it.getOrNull(0) to it.getOrNull(1).orEmpty()
    }
    val root = when (volume) {
        "primary" -> Environment.getExternalStorageDirectory().absolutePath
        null -> return null
        // Removable volumes are mounted under /storage/<UUID>.
        else -> "/storage/$volume"
    }
    return if (relative.isEmpty()) root else "$root/$relative"
}
