// Lists previous on-device backups for one-tap restore, and allows importing a
// backup file from elsewhere via the system document picker (the Android
// counterpart of the iOS file importer). Restoring replaces the whole store,
// so it always sits behind a confirmation dialog.

package com.christianmolinari.scorecard.ui.settings

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.data.backup.BackupFile
import com.christianmolinari.scorecard.ui.components.AppBackground
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.GameFormatting
import com.christianmolinari.scorecard.ui.components.PlayfulSectionHeader
import com.christianmolinari.scorecard.ui.components.StatusAlertDialog
import com.christianmolinari.scorecard.ui.components.SwipeToDeleteBox
import com.christianmolinari.scorecard.ui.components.WorkingOverlay
import com.christianmolinari.scorecard.ui.theme.ThemeColors
import java.io.File
import kotlinx.coroutines.launch

// StatusMessage (the success/error alert payload) is declared in SettingsScreen.kt.

// The backup awaiting restore confirmation — from the list or the importer.
private sealed interface RestoreSource {
    data class FromFile(val file: File) : RestoreSource
    data class FromUri(val uri: Uri) : RestoreSource
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupListScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var backups by remember { mutableStateOf<List<BackupFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isWorking by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<RestoreSource?>(null) }
    var statusMessage by remember { mutableStateOf<StatusMessage?>(null) }

    suspend fun load() {
        isLoading = true
        backups = container.backupStorage.listBackups()
        isLoading = false
    }

    LaunchedEffect(Unit) { load() }

    suspend fun restore(source: RestoreSource) {
        isWorking = true
        try {
            val text = when (source) {
                is RestoreSource.FromFile -> container.backupStorage.read(source.file)
                is RestoreSource.FromUri -> container.backupStorage.read(source.uri)
            }
            val snapshot = container.backupService.restore(text)
            statusMessage = StatusMessage(
                title = "Restore Complete",
                body = "Restored ${snapshot.players.size} players, ${snapshot.teams.size} teams, " +
                    "and ${snapshot.games.size} games.",
            )
        } catch (e: Exception) {
            statusMessage = StatusMessage(title = "Restore Failed", body = e.message ?: "Something went wrong.")
        } finally {
            isWorking = false
        }
    }

    // The open-document contract grants read access to the returned uri, so no
    // extra permission dance is needed before reading it.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingRestore = RestoreSource.FromUri(uri)
        }
    }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Restore") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (backups.isEmpty() && !isLoading) {
                    item(key = "empty") {
                        EmptyBackupsCard()
                    }
                } else if (backups.isNotEmpty()) {
                    item(key = "header-backups") {
                        PlayfulSectionHeader(title = "Available Backups", icon = Icons.Filled.Storage)
                    }
                    items(backups, key = { it.name }) { file ->
                        SwipeToDeleteBox(
                            onDelete = {
                                scope.launch {
                                    container.backupStorage.delete(file.file)
                                    load()
                                }
                            },
                        ) {
                            BackupRow(
                                file = file,
                                onClick = { pendingRestore = RestoreSource.FromFile(file.file) },
                            )
                        }
                    }
                    item(key = "footer-backups") {
                        Text(
                            text = "Choose a backup to replace all current data with its contents. " +
                                "Swipe to delete a backup file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }

                item(key = "import") {
                    CardTile(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { if (!isWorking) importLauncher.launch(arrayOf("application/json")) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                tint = ThemeColors.accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Import from Files…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = ThemeColors.accent,
                            )
                        }
                    }
                }
            }
        }

        WorkingOverlay(visible = isWorking)
    }

    pendingRestore?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Restoring replaces everything currently in ScoreCard with the contents " +
                        "of this backup. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRestore = null
                        scope.launch { restore(source) }
                    },
                ) {
                    Text("Replace All Data", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("Cancel") }
            },
        )
    }

    statusMessage?.let { message ->
        StatusAlertDialog(title = message.title, body = message.body, onDismiss = { statusMessage = null })
    }
}

@Composable
private fun BackupRow(file: BackupFile, onClick: () -> Unit) {
    val context = LocalContext.current
    CardTile(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Smartphone,
                contentDescription = null,
                tint = ThemeColors.accent,
                modifier = Modifier.size(24.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = GameFormatting.dateTime(file.date),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "On this device · ${Formatter.formatShortFileSize(context, file.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// Centered placeholder, mirroring the iOS ContentUnavailableView in a section.
@Composable
private fun EmptyBackupsCard() {
    CardTile(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No Backups Yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Use “Back Up Now” in Settings to create one, or import a backup file " +
                    "you saved elsewhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
