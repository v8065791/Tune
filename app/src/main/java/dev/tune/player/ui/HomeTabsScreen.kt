package dev.tune.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tune.player.data.HomeTab

/**
 * Chooses which tabs the home screen shows and in what order.
 *
 * Enabled tabs are listed first in their display order, followed by the disabled ones. Reordering
 * uses explicit up/down buttons rather than drag-and-drop — with at most six rows it's less
 * fiddly, and it stays usable for anyone driving the UI with a screen reader.
 */
@Composable
fun HomeTabsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val tabs by vm.homeTabs.collectAsState()
    val disabled = HomeTab.entries.filterNot { it in tabs }
    val rows = tabs + disabled

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home tabs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    text = "At least one tab must stay enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            items(rows, key = { it.name }) { tab ->
                val enabled = tab in tabs
                val index = tabs.indexOf(tab)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = enabled,
                        // Refuse to remove the last tab, which would leave an empty home screen.
                        enabled = !enabled || tabs.size > 1,
                        onCheckedChange = { checked ->
                            val next = if (checked) tabs + tab else tabs - tab
                            if (next.isNotEmpty()) vm.edit { setHomeTabs(next) }
                        },
                    )
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )

                    IconButton(
                        onClick = { vm.edit { setHomeTabs(tabs.swapped(index, index - 1)) } },
                        enabled = enabled && index > 0,
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move ${tab.label} up")
                    }
                    IconButton(
                        onClick = { vm.edit { setHomeTabs(tabs.swapped(index, index + 1)) } },
                        enabled = enabled && index in 0 until tabs.lastIndex,
                    ) {
                        Icon(
                            Icons.Default.ArrowDownward,
                            contentDescription = "Move ${tab.label} down",
                        )
                    }
                }
            }
        }
    }
}

/** Returns a copy with [a] and [b] exchanged, or the original if either index is out of bounds. */
private fun <T> List<T>.swapped(a: Int, b: Int): List<T> {
    if (a !in indices || b !in indices) return this
    return toMutableList().apply {
        val tmp = this[a]
        this[a] = this[b]
        this[b] = tmp
    }
}
