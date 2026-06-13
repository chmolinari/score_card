// The Players tab: browse, add, edit, and delete the roster of people.

package com.christianmolinari.scorecard.ui.players

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.domain.CompetitorSortOrder
import com.christianmolinari.scorecard.domain.CompetitorSorter
import com.christianmolinari.scorecard.domain.NameComparator
import com.christianmolinari.scorecard.domain.Tally
import com.christianmolinari.scorecard.domain.playerTally
import com.christianmolinari.scorecard.ui.components.AppBackground
import com.christianmolinari.scorecard.ui.components.Avatar
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.EmptyState
import com.christianmolinari.scorecard.ui.components.PlayerEditDialog
import com.christianmolinari.scorecard.ui.components.SwipeToDeleteBox
import com.christianmolinari.scorecard.ui.components.TallyBadge
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayersScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val players by container.database.playerDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val teams by container.database.teamDao().observeAllWithMembers()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val games by container.database.gameDao().observeAllWithDetails()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val sortOrder by container.prefs.playersSortOrder
        .collectAsStateWithLifecycle(initialValue = CompetitorSortOrder.NameAscending)

    var editingPlayer by remember { mutableStateOf<PlayerEntity?>(null) }
    var isAddingPlayer by remember { mutableStateOf(false) }

    // Players in the user's chosen order. "Score" sorts can't live in the
    // database query because the tally is computed on the fly, so we re-sort here.
    val tallies = remember(players, games) {
        players.associate { it.id to playerTally(it.id, games) }
    }
    val sortedPlayers = remember(players, tallies, sortOrder) {
        CompetitorSorter.sorted(players, sortOrder, name = { it.name }, tally = { tallies[it.id] ?: Tally() })
    }
    // Which teams each player belongs to, for the row's caption line.
    val teamNamesByPlayer = remember(teams) {
        val map = mutableMapOf<Long, MutableList<String>>()
        for (team in teams) {
            for (member in team.members) {
                map.getOrPut(member.id) { mutableListOf() }.add(team.team.name)
            }
        }
        map.mapValues { (_, names) -> names.sortedWith(NameComparator) }
    }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Players") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    actions = {
                        if (players.isNotEmpty()) {
                            SortMenu(current = sortOrder) { order ->
                                scope.launch { container.prefs.setPlayersSortOrder(order) }
                            }
                        }
                        IconButton(onClick = { isAddingPlayer = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add Player")
                        }
                    },
                )
            },
        ) { innerPadding ->
            if (players.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Filled.PersonAdd,
                        title = "No Players",
                        description = "Add the people who will be keeping score.",
                        actionLabel = "Add Player",
                        onAction = { isAddingPlayer = true },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(sortedPlayers, key = { it.id }) { player ->
                        // Deletion targets the displayed (sorted) row, not the raw query order.
                        SwipeToDeleteBox(
                            onDelete = {
                                scope.launch { container.database.playerDao().delete(player) }
                            },
                        ) {
                            PlayerRow(
                                player = player,
                                teamNames = teamNamesByPlayer[player.id].orEmpty(),
                                tally = tallies[player.id] ?: Tally(),
                                onClick = { editingPlayer = player },
                            )
                        }
                    }
                }
            }
        }
    }

    if (isAddingPlayer) {
        PlayerEditDialog(
            container = container,
            existing = null,
            onDismiss = { isAddingPlayer = false },
        )
    }
    editingPlayer?.let { player ->
        PlayerEditDialog(
            container = container,
            existing = player,
            onDismiss = { editingPlayer = null },
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
private fun PlayerRow(
    player: PlayerEntity,
    teamNames: List<String>,
    tally: Tally,
    onClick: () -> Unit,
) {
    CardTile(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(name = player.name, size = 46.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    player.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (teamNames.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.Groups,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            teamNames.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TallyBadge(tally = tally)
            }
        }
    }
}
