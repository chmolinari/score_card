package com.christianmolinari.scorecard.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.data.db.DISTANT_PAST
import com.christianmolinari.scorecard.data.db.GameNameEntity
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.TeamEntity
import com.christianmolinari.scorecard.data.db.TeamWithMembers
import com.christianmolinari.scorecard.domain.NameComparator
import com.christianmolinari.scorecard.domain.RosterCheck
import java.time.Instant
import kotlinx.coroutines.launch

// Dialogs for creating or renaming players, teams and game names. Ports of the
// iOS PlayerEditView / TeamEditView / GameNameEditView sheets. `existing` nil
// means "create"; on create the freshly inserted row (re-read with its
// generated id) is handed to `onCreated` so callers like New Game can
// auto-select what was just created. Editing updates in place and does NOT
// call `onCreated`.

@Composable
fun PlayerEditDialog(
    container: AppContainer,
    existing: PlayerEntity?,
    onDismiss: () -> Unit,
    onCreated: (PlayerEntity) -> Unit = {},
) {
    val playersFlow = remember(container) { container.database.playerDao().observeAll() }
    val allPlayers by playersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    NameFieldDialog(
        title = if (existing != null) "Edit Player" else "New Player",
        placeholder = "Player name",
        initialName = existing?.name ?: "",
        duplicateError = { trimmed ->
            // Player names must be unique (case-insensitive), excluding the row
            // being edited.
            val clashes = allPlayers.any { other ->
                other.id != existing?.id && other.name.equals(trimmed, ignoreCase = true)
            }
            if (clashes) "A player named “$trimmed” already exists." else null
        },
        onSave = { trimmed ->
            scope.launch {
                val dao = container.database.playerDao()
                if (existing != null) {
                    dao.update(existing.copy(name = trimmed))
                } else {
                    val id = dao.insert(PlayerEntity(name = trimmed, createdAt = Instant.now()))
                    dao.getAll().firstOrNull { it.id == id }?.let(onCreated)
                }
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
fun GameNameEditDialog(
    container: AppContainer,
    existing: GameNameEntity?,
    onDismiss: () -> Unit,
    onCreated: (GameNameEntity) -> Unit = {},
) {
    val namesFlow = remember(container) { container.database.gameNameDao().observeAll() }
    val allNames by namesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    NameFieldDialog(
        title = if (existing != null) "Edit Game Name" else "New Game Name",
        placeholder = "Game name (e.g. Scopa, Briscola)",
        initialName = existing?.name ?: "",
        duplicateError = { trimmed ->
            // Game names must be unique so the New Game picker has no dupes.
            val clashes = allNames.any { other ->
                other.id != existing?.id && other.name.equals(trimmed, ignoreCase = true)
            }
            if (clashes) "A game named “$trimmed” already exists." else null
        },
        onSave = { trimmed ->
            scope.launch {
                val dao = container.database.gameNameDao()
                if (existing != null) {
                    dao.update(existing.copy(name = trimmed))
                } else {
                    // lastUsedAt stays at DISTANT_PAST until a game first uses
                    // this name, so never-used names sort last by recency.
                    val id = dao.insert(
                        GameNameEntity(
                            name = trimmed,
                            createdAt = Instant.now(),
                            lastUsedAt = DISTANT_PAST,
                        )
                    )
                    dao.getAll().firstOrNull { it.id == id }?.let(onCreated)
                }
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
fun TeamEditDialog(
    container: AppContainer,
    existing: TeamWithMembers?,
    onDismiss: () -> Unit,
    onCreated: (TeamWithMembers) -> Unit = {},
) {
    val teamsFlow = remember(container) { container.database.teamDao().observeAllWithMembers() }
    val playersFlow = remember(container) { container.database.playerDao().observeAll() }
    val allTeams by teamsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allPlayers by playersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val sortedPlayers = remember(allPlayers) {
        allPlayers.sortedWith(compareBy(NameComparator) { it.name })
    }
    val scope = rememberCoroutineScope()

    var name by remember(existing) { mutableStateOf(existing?.team?.name ?: "") }
    var selectedIds by remember(existing) {
        mutableStateOf(existing?.members?.map { it.id }?.toSet() ?: emptySet())
    }
    var isCreatingPlayer by remember { mutableStateOf(false) }

    val trimmed = name.trim()
    // Team names must be unique (case-insensitive), excluding the row being
    // edited.
    val nameError = if (trimmed.isEmpty()) {
        null
    } else {
        val clashes = allTeams.any { other ->
            other.team.id != existing?.team?.id && other.team.name.equals(trimmed, ignoreCase = true)
        }
        if (clashes) "A team named “$trimmed” already exists." else null
    }
    val canSave = trimmed.isNotEmpty() &&
        selectedIds.size >= RosterCheck.MINIMUM_TEAM_SIZE &&
        nameError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Edit Team" else "New Team") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Team name") },
                    singleLine = true,
                    isError = nameError != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (nameError != null) {
                    Text(
                        nameError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Text(
                    "Members",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                if (allPlayers.isEmpty()) {
                    Text(
                        "No players yet. Create one to add it to this team.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                    ) {
                        items(sortedPlayers, key = { it.id }) { player ->
                            val selected = player.id in selectedIds
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds =
                                            if (selected) selectedIds - player.id
                                            else selectedIds + player.id
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(player.name, modifier = Modifier.weight(1f))
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { isCreatingPlayer = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("New Player")
                }
                Text(
                    "A team needs at least two members.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    scope.launch {
                        val dao = container.database.teamDao()
                        // Keep member ids in display order; the junction table
                        // itself is unordered.
                        val memberIds = sortedPlayers.filter { it.id in selectedIds }.map { it.id }
                        if (existing != null) {
                            dao.update(existing.team.copy(name = trimmed))
                            dao.setMembers(existing.team.id, memberIds)
                        } else {
                            val id = dao.insert(TeamEntity(name = trimmed, createdAt = Instant.now()))
                            dao.setMembers(id, memberIds)
                            dao.getAllWithMembers().firstOrNull { it.team.id == id }?.let(onCreated)
                        }
                        onDismiss()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (isCreatingPlayer) {
        // Nested creator (mirrors the iOS sheet-on-sheet); the new player is
        // auto-selected so it lands in the team being built.
        PlayerEditDialog(
            container = container,
            existing = null,
            onDismiss = { isCreatingPlayer = false },
            onCreated = { created -> selectedIds = selectedIds + created.id },
        )
    }
}

// Shared single-text-field dialog used by the player and game-name editors:
// trimmed name, Save disabled when blank or clashing, error line in red.
@Composable
private fun NameFieldDialog(
    title: String,
    placeholder: String,
    initialName: String,
    duplicateError: (String) -> String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val error = if (trimmed.isEmpty()) null else duplicateError(trimmed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty() && error == null,
                onClick = { onSave(trimmed) },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
