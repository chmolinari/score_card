// The Teams tab: browse, add, edit, and delete teams (groups of players).

package com.christianmolinari.scorecard.ui.teams

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.data.db.TeamWithMembers
import com.christianmolinari.scorecard.domain.CompetitorSortOrder
import com.christianmolinari.scorecard.domain.CompetitorSorter
import com.christianmolinari.scorecard.domain.RosterCheck
import com.christianmolinari.scorecard.domain.Tally
import com.christianmolinari.scorecard.domain.rosterSummary
import com.christianmolinari.scorecard.domain.sortedMembers
import com.christianmolinari.scorecard.domain.teamTally
import com.christianmolinari.scorecard.ui.components.AppBackground
import com.christianmolinari.scorecard.ui.components.Avatar
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.EmptyState
import com.christianmolinari.scorecard.ui.components.SwipeToDeleteBox
import com.christianmolinari.scorecard.ui.components.TallyBadge
import com.christianmolinari.scorecard.ui.components.TeamEditDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val teams by container.teamDao.observeAllWithMembers()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val games by container.gameDao.observeAllWithDetails()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val sortOrder by container.prefs.teamsSortOrder
        .collectAsStateWithLifecycle(initialValue = CompetitorSortOrder.NameAscending)

    var editingTeam by remember { mutableStateOf<TeamWithMembers?>(null) }
    // A team a swipe has proposed deleting, held until the user confirms.
    var pendingDeletion by remember { mutableStateOf<TeamWithMembers?>(null) }
    var isAddingTeam by remember { mutableStateOf(false) }

    // Teams in the user's chosen order. "Score" sorts can't live in the
    // database query because the tally is computed on the fly, so we re-sort here.
    val tallies = remember(teams, games) {
        teams.associate { it.team.id to teamTally(it.team.id, games) }
    }
    val sortedTeams = remember(teams, tallies, sortOrder) {
        CompetitorSorter.sorted(teams, sortOrder, name = { it.team.name }, tally = { tallies[it.team.id] ?: Tally() })
    }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Teams") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    actions = {
                        if (teams.isNotEmpty()) {
                            SortMenu(current = sortOrder) { order ->
                                scope.launch { container.prefs.setTeamsSortOrder(order) }
                            }
                        }
                        IconButton(onClick = { isAddingTeam = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add Team")
                        }
                    },
                )
            },
        ) { innerPadding ->
            if (teams.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Filled.GroupAdd,
                        title = "No Teams",
                        description = "Group players into teams to score them together. Players can still play on their own.",
                        actionLabel = "Add Team",
                        onAction = { isAddingTeam = true },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(sortedTeams, key = { it.team.id }) { team ->
                        // Deletion targets the displayed (sorted) row, not the raw query order.
                        // The swipe only proposes it; the confirmation commits.
                        SwipeToDeleteBox(
                            onDelete = { pendingDeletion = team },
                            confirmFirst = true,
                        ) {
                            TeamRow(
                                team = team,
                                tally = tallies[team.team.id] ?: Tally(),
                                onClick = { editingTeam = team },
                            )
                        }
                    }
                }
            }
        }
    }

    if (isAddingTeam) {
        TeamEditDialog(
            container = container,
            existing = null,
            onDismiss = { isAddingTeam = false },
        )
    }
    editingTeam?.let { team ->
        TeamEditDialog(
            container = container,
            existing = team,
            onDismiss = { editingTeam = null },
        )
    }

    // Makes the blast radius explicit: unlike deleting a player, this removes
    // only the grouping — the people and the past results both survive.
    pendingDeletion?.let { team ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Delete ${team.team.name}?") },
            text = {
                Text(
                    RosterCheck.teamDeletionMessage(
                        teamName = team.team.name,
                        memberCount = team.sortedMembers.size,
                    ) + " This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeletion = null
                        scope.launch { container.teamDao.delete(team.team) }
                    },
                ) {
                    Text("Delete Team", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) { Text("Cancel") }
            },
        )
    }
}

// A menu to pick how the list is ordered; the choice is remembered (Prefs).
@Composable
private fun SortMenu(current: CompetitorSortOrder, onSelect: (CompetitorSortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            // Announce the active order to accessibility services ("Sort, Name (A–Z)").
            Icon(Icons.Filled.SwapVert, contentDescription = "Sort, ${current.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CompetitorSortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    leadingIcon = {
                        Icon(
                            if (order.isAscendingArrow) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = if (order == current) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(order)
                    },
                )
            }
        }
    }
}

@Composable
private fun TeamRow(
    team: TeamWithMembers,
    tally: Tally,
    onClick: () -> Unit,
) {
    CardTile(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(name = team.team.name, icon = Icons.Filled.Groups, size = 46.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    team.team.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    team.rosterSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TallyBadge(tally = tally)
            }
        }
    }
}
