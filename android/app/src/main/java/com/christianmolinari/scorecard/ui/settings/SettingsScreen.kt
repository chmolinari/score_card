// The Settings tab: explains where data lives and offers gameplay options,
// data stats, manual backup/share/restore entry points, and a full reset.
// Unlike iOS there is no CloudKit sync on Android — data is on-device only and
// moves between devices (and platforms) via backup files, so the storage card
// says exactly that instead of faking a sync status.

package com.christianmolinari.scorecard.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.data.log.ActionLogSize
import com.christianmolinari.scorecard.domain.DealingDirection
import com.christianmolinari.scorecard.domain.DrawDealingRule
import com.christianmolinari.scorecard.ui.components.AppBackground
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.GameFormatting
import com.christianmolinari.scorecard.ui.components.PlayfulSectionHeader
import com.christianmolinari.scorecard.ui.components.StatusAlertDialog
import com.christianmolinari.scorecard.ui.components.WorkingOverlay
import com.christianmolinari.scorecard.ui.theme.ThemeColors
import java.io.File
import java.time.Instant
import kotlinx.coroutines.launch

// Small wrapper so a success/error result can drive the status alert (the
// Android counterpart of the iOS StatusMessage). Shared with BackupListScreen.
internal data class StatusMessage(val title: String, val body: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenBackups: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenActionLog: () -> Unit,
) {
    val players by container.playerDao.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val teams by container.teamDao.observeAllWithMembers()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val games by container.gameDao.observeAllWithDetails()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val dealingDirection by container.prefs.dealingDirection
        .collectAsStateWithLifecycle(initialValue = DealingDirection.CounterClockwise)
    val drawDealingRule by container.prefs.drawDealingRule
        .collectAsStateWithLifecycle(initialValue = DrawDealingRule.Ask)

    val scope = rememberCoroutineScope()
    val allowNegativeScores by container.prefs.allowNegativeScores
        .collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current

    // Backup / reset state. (Restore lives in BackupListScreen.)
    var isWorking by remember { mutableStateOf(false) }
    var lastBackup by remember { mutableStateOf<File?>(null) }
    var lastBackupDate by remember { mutableStateOf<Instant?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<StatusMessage?>(null) }

    // Action log.
    val actionLogEnabled by container.prefs.actionLogEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val actionLogMaxMiB by container.prefs.actionLogMaxMiB
        .collectAsStateWithLifecycle(initialValue = ActionLogSize.DEFAULT_MIB)
    var showLogDeleteConfirmation by remember { mutableStateOf(false) }
    // Bumped after a delete so the displayed size stops showing bytes that are
    // gone; the file has no Flow to observe.
    var logSizeRevision by remember { mutableStateOf(0) }
    val logBytes = remember(logSizeRevision, actionLogEnabled, games, players, teams) {
        container.actionLog.totalBytes()
    }

    val isEmptyStore = players.isEmpty() && teams.isEmpty() && games.isEmpty()

    fun backUpNow() {
        scope.launch {
            isWorking = true
            try {
                val json = container.backupService.exportJson()
                lastBackup = container.backupStorage.write(json)
                lastBackupDate = Instant.now()
                statusMessage = StatusMessage(
                    title = "Backup Complete",
                    body = "Saved ${players.size} players, ${teams.size} teams, and ${games.size} games to this device.",
                )
            } catch (e: Exception) {
                statusMessage = StatusMessage(title = "Backup Failed", body = e.message ?: "Something went wrong.")
            } finally {
                isWorking = false
            }
        }
    }

    fun shareLatestBackup() {
        val file = lastBackup ?: return
        val uri = container.backupStorage.shareUri(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Backup"))
    }

    fun reset() {
        scope.launch {
            isWorking = true
            try {
                container.backupService.eraseAll()
                lastBackup = null
                lastBackupDate = null
                statusMessage = StatusMessage(
                    title = "Data Deleted",
                    body = "All players, teams, and games have been removed.",
                )
            } catch (e: Exception) {
                statusMessage = StatusMessage(title = "Couldn't Delete Data", body = e.message ?: "Something went wrong.")
            } finally {
                isWorking = false
            }
        }
    }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Settings") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "storage") {
                    SettingsSection(
                        footer = "Players, teams, and games are stored on this device. To move them to " +
                            "another device — or to the iOS app — save a backup file and share it.",
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Smartphone,
                                contentDescription = null,
                                tint = ThemeColors.teal,
                                modifier = Modifier.size(28.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "On This Device",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Your data lives locally and is included in backup files.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item(key = "help") {
                    SettingsSection(
                        footer = "A guide to setting up a game, keeping score hand by hand, and " +
                            "correcting a result after the game is over.",
                    ) {
                        ActionRow(
                            icon = Icons.AutoMirrored.Filled.HelpOutline,
                            label = "How to Use ScoreCard",
                            onClick = onOpenHelp,
                        )
                    }
                }

                item(key = "gameplay") {
                    SettingsSection(
                        title = "Gameplay",
                        footer = "The direction the deal passes around the table after each hand.",
                    ) {
                        DealingDirection.entries.forEachIndexed { index, direction ->
                            if (index > 0) HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    // Disabled while a backup/reset is in flight, matching
                                    // the iOS list's .disabled(isWorking).
                                    .clickable(enabled = !isWorking) {
                                        scope.launch { container.prefs.setDealingDirection(direction) }
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = if (direction == DealingDirection.CounterClockwise) {
                                        Icons.Filled.RotateLeft
                                    } else {
                                        Icons.Filled.RotateRight
                                    },
                                    contentDescription = null,
                                    tint = ThemeColors.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = direction.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                RadioButton(selected = direction == dealingDirection, onClick = null)
                            }
                        }
                    }
                }

                item(key = "gameplay-draw") {
                    SettingsSection(
                        title = "After a Draw",
                        footer = "Who deals the next hand when a hand ends in a draw and nobody scores.",
                    ) {
                        DrawDealingRule.entries.forEachIndexed { index, rule ->
                            if (index > 0) HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isWorking) {
                                        scope.launch { container.prefs.setDrawDealingRule(rule) }
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = when (rule) {
                                        DrawDealingRule.Redeal -> Icons.Filled.Refresh
                                        DrawDealingRule.PassOn -> Icons.AutoMirrored.Filled.ArrowForward
                                        DrawDealingRule.Ask -> Icons.Filled.QuestionMark
                                    },
                                    contentDescription = null,
                                    tint = ThemeColors.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = rule.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                RadioButton(selected = rule == drawDealingRule, onClick = null)
                            }
                        }
                    }
                }

                item(key = "scoring") {
                    SettingsSection(
                        title = "Scoring",
                        footer = "When off, a total stops at zero everywhere — subtracting, " +
                            "correcting a finished game, and registering a past one. Turn on " +
                            "for games that go negative, like Spades or Pinochle.",
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !isWorking) {
                                    scope.launch {
                                        container.prefs.setAllowNegativeScores(!allowNegativeScores)
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = null,
                                tint = ThemeColors.accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Allow scores below zero",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = allowNegativeScores, onCheckedChange = null)
                        }
                    }
                }

                item(key = "yourData") {
                    SettingsSection(title = "Your Data") {
                        LabeledRow(label = "Players", value = players.size.toString())
                        HorizontalDivider()
                        LabeledRow(label = "Teams", value = teams.size.toString())
                        HorizontalDivider()
                        LabeledRow(label = "Games", value = games.size.toString())
                    }
                }

                item(key = "backup") {
                    val date = lastBackupDate
                    SettingsSection(
                        title = "Backup & Restore",
                        footer = if (lastBackup != null && date != null) {
                            "Last backup: ${GameFormatting.dateTime(date)} — saved to this device."
                        } else {
                            "Save a snapshot of all your data as a file on this device. " +
                                "Share it to move your data to another device or another platform."
                        },
                    ) {
                        ActionRow(
                            icon = Icons.Filled.Backup,
                            label = "Back Up Now",
                            enabled = !isEmptyStore && !isWorking,
                            onClick = ::backUpNow,
                        )
                        if (lastBackup != null) {
                            HorizontalDivider()
                            ActionRow(
                                icon = Icons.Filled.Share,
                                label = "Share Latest Backup",
                                enabled = !isWorking,
                                onClick = ::shareLatestBackup,
                            )
                        }
                        HorizontalDivider()
                        ActionRow(
                            icon = Icons.Filled.Restore,
                            label = "Restore from Backup…",
                            enabled = !isWorking,
                            onClick = onOpenBackups,
                        )
                    }
                }

                item(key = "logging") {
                    SettingsSection(
                        title = "Logging",
                        footer = if (actionLogEnabled) {
                            "Records every change to your players, teams and games — including " +
                                "each score — with the time it happened, so an unexpected result " +
                                "can be traced later. Kept on this device only and never included " +
                                "in a backup. The oldest entries are dropped once the log reaches " +
                                "its maximum size. Turn recording off to delete it."
                        } else {
                            "Recording is off, so nothing new is being written. The existing log " +
                                "is kept until you delete it."
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !isWorking) {
                                    scope.launch {
                                        container.prefs.setActionLogEnabled(!actionLogEnabled)
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Description,
                                contentDescription = null,
                                tint = ThemeColors.accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Record actions",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = actionLogEnabled, onCheckedChange = null)
                        }
                        HorizontalDivider()
                        ChoiceRow(
                            label = "Maximum size",
                            options = ActionLogSize.choices,
                            selected = actionLogMaxMiB,
                            optionLabel = { "$it MiB" },
                            enabled = actionLogEnabled && !isWorking,
                        ) { chosen ->
                            scope.launch {
                                container.prefs.setActionLogMaxMiB(chosen)
                                // Apply a lowered maximum now, not at the next write.
                                container.actionLog.enforceLimit(chosen)
                                logSizeRevision++
                            }
                        }
                        HorizontalDivider()
                        ActionRow(
                            icon = Icons.Filled.Description,
                            label = "View Log",
                            onClick = onOpenActionLog,
                        )
                        HorizontalDivider()
                        ActionRow(
                            icon = Icons.Filled.Share,
                            label = "Share Log",
                            enabled = logBytes > 0,
                            onClick = { shareActionLog(context, container) },
                        )
                        HorizontalDivider()
                        // Offered only while recording is off, so a delete can
                        // never race a live write.
                        ActionRow(
                            icon = Icons.Filled.Delete,
                            label = "Delete Log",
                            enabled = !actionLogEnabled && logBytes > 0,
                            destructive = true,
                            onClick = { showLogDeleteConfirmation = true },
                        )
                    }
                }

                item(key = "danger") {
                    SettingsSection(
                        footer = "Erases all players, teams, and games to start fresh. " +
                            "Back up first if you might want the data later.",
                    ) {
                        ActionRow(
                            icon = Icons.Filled.Delete,
                            label = "Delete All Data",
                            enabled = !isEmptyStore && !isWorking,
                            destructive = true,
                            onClick = { showResetConfirmation = true },
                        )
                    }
                }

                item(key = "about") {
                    SettingsSection(title = "About") {
                        LabeledRow(label = "App", value = "ScoreCard")
                        HorizontalDivider()
                        LabeledRow(label = "Version", value = appVersion())
                    }
                }
            }
        }

        WorkingOverlay(visible = isWorking)
    }

    if (showLogDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogDeleteConfirmation = false },
            title = { Text("Delete the action log?") },
            text = {
                Text(
                    "This removes the record of past actions from this device. " +
                        "Your players, teams and games are not affected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogDeleteConfirmation = false
                        container.actionLog.deleteAll()
                        logSizeRevision++
                    },
                ) {
                    Text("Delete Log", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogDeleteConfirmation = false }) { Text("Cancel") }
            },
        )
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Delete all data?") },
            // The iOS message minus its iCloud sentence — there's no sync to warn about here.
            text = { Text("This permanently removes every player, team, and game. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation = false
                        reset()
                    },
                ) {
                    Text("Delete Everything", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) { Text("Cancel") }
            },
        )
    }

    statusMessage?.let { message ->
        StatusAlertDialog(title = message.title, body = message.body, onDismiss = { statusMessage = null })
    }
}

// MARK-equivalent: section building blocks.

// A titled card with an optional explanatory footer, mirroring an iOS Form section.
@Composable
private fun SettingsSection(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            PlayfulSectionHeader(title = title)
        }
        CardTile(modifier = Modifier.fillMaxWidth(), content = content)
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

// Label on the left, value on the right — like iOS LabeledContent.
@Composable
private fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// A tappable icon+label row, accent-tinted (error-tinted when destructive).
@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else ThemeColors.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

// "versionName (versionCode)", like the iOS "version (build)" pair.
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = info.versionName ?: "1.0"
            "$name (${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("1.0")
    }
}

// A label with the current choice on the right, expanding to a menu — the
// Android stand-in for an iOS Picker row.
@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(vertical = 10.dp)
                .alpha(if (enabled) 1f else 0.4f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = optionLabel(selected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

// Hands both log segments to another app. Uses the same FileProvider authority
// as backup sharing, so no new manifest entry is needed.
private fun shareActionLog(context: Context, container: AppContainer) {
    val files = container.actionLog.shareableFiles()
    if (files.isEmpty()) return
    val uris = ArrayList(
        files.map { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    )
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/json"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share action log"))
}
