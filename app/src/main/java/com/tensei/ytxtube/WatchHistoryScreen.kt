package com.tensei.ytxtube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun WatchHistoryScreen(onBack: () -> Unit, onOpen: (WatchEntry) -> Unit) {
    val videos by WatchHistory.videos.collectAsState()
    val days by WatchHistory.retention.collectAsState()
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        WatchHistory.observe(true)
        onDispose { WatchHistory.observe(false) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            WatchHistory.refresh()
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column {
            ScreenHeader(title = "Watch History") {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                TextButton(onClick = { confirmClear = true }, enabled = videos.isNotEmpty()) {
                    Text("Clear history")
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Keep history for", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WatchHistoryPolicy.retentionOptions.forEach { option ->
                                FilterChip(
                                    selected = days == option,
                                    onClick = { WatchHistory.setRetention(option) },
                                    label = { Text("$option days") },
                                )
                            }
                        }
                        Text(
                            "History stays on this device. Extending retention cannot restore deleted entries.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (videos.isEmpty()) {
                    item { EmptyState(Icons.Rounded.History, "No watch history", "Videos appear here after you watch them.") }
                }
                items(videos, key = { it.video.id }) { entry ->
                    VideoRow(entry.video, onClick = { onOpen(entry) }) {
                        IconButton(onClick = { WatchHistory.remove(entry.video.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove ${entry.video.title} from history")
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear watch history?") },
            text = { Text("This permanently removes all entries from this device’s watch history.") },
            confirmButton = {
                TextButton(onClick = {
                    WatchHistory.clear()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
