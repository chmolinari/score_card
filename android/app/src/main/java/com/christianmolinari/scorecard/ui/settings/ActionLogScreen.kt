// Reads back the action log, newest first. Only the tail of the live segment is
// decoded (see ActionLog.recentEntries), so this opens instantly even when the
// log is at its 100 MiB maximum. Port of the iOS ActionLogView.

package com.christianmolinari.scorecard.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.data.log.ActionLogEntry
import com.christianmolinari.scorecard.ui.components.AppBackground
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionLogScreen(container: AppContainer, onBack: () -> Unit) {
    // Read off the main thread: decoding the tail of a large file should not
    // stall the navigation animation.
    val entries by produceState(initialValue = null as List<ActionLogEntry>?, container) {
        value = withContext(Dispatchers.IO) { container.actionLog.recentEntries() }
    }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Action Log") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { innerPadding ->
            val rows = entries
            when {
                rows == null -> Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Reading log…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                rows.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Filled.Description,
                        title = "No Actions Recorded",
                        description = "Actions appear here as you use the app. If recording is " +
                            "switched off in Settings, nothing new is added.",
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows) { entry -> EntryRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: ActionLogEntry) {
    CardTile(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.action,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = entry.timestamp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.name?.takeIf { it.isNotEmpty() }?.let { name ->
                Text(text = name, style = MaterialTheme.typography.bodyMedium)
            }
            subtitle(entry)?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// The game id plus any extras on one line — enough to correlate rows without
// opening the raw file.
private fun subtitle(entry: ActionLogEntry): String? {
    val parts = buildList {
        entry.gameId?.let { add("game $it") }
        entry.detail?.toSortedMap()?.forEach { (key, value) -> add("$key: $value") }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}
