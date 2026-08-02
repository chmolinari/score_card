// The Games tab: shows games in progress and the full history of finished
// games. New games are created from here; tapping a game opens its scoreboard
// (if open) or its history detail (if closed).

package com.christianmolinari.scorecard.ui.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.data.db.GameWithDetails
import com.christianmolinari.scorecard.domain.displayName
import com.christianmolinari.scorecard.domain.isDraw
import com.christianmolinari.scorecard.domain.isEdited
import com.christianmolinari.scorecard.domain.isOpen
import com.christianmolinari.scorecard.domain.leader
import com.christianmolinari.scorecard.domain.rankedParticipants
import com.christianmolinari.scorecard.domain.totalScore
import com.christianmolinari.scorecard.ui.components.AppBackground
import com.christianmolinari.scorecard.ui.components.Avatar
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.EmptyState
import com.christianmolinari.scorecard.ui.components.GameFormatting
import com.christianmolinari.scorecard.ui.components.PlayfulSectionHeader
import com.christianmolinari.scorecard.ui.components.SwipeToDeleteBox
import com.christianmolinari.scorecard.ui.theme.ThemeColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    container: AppContainer,
    onOpenGame: (Long) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onNewGame: () -> Unit,
    onRegisterGame: () -> Unit,
) {
    val games by container.gameDao.observeAllWithDetails()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    // The "+" button offers both ways to add a game (like the iOS Menu).
    var isAddMenuOpen by remember { mutableStateOf(false) }

    val openGames = games.filter { it.isOpen }
    val closedGames = games.filter { !it.isOpen }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Games") },
                    actions = {
                        Box {
                            IconButton(onClick = { isAddMenuOpen = true }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add Game")
                            }
                            DropdownMenu(
                                expanded = isAddMenuOpen,
                                onDismissRequest = { isAddMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("New Game") },
                                    leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                                    onClick = {
                                        isAddMenuOpen = false
                                        onNewGame()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Register Past Game") },
                                    leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                                    onClick = {
                                        isAddMenuOpen = false
                                        onRegisterGame()
                                    },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            if (games.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Filled.Style,
                        title = "No Games Yet",
                        description = "Start a game to begin keeping score.",
                        actionLabel = "New Game",
                        onAction = onNewGame,
                        secondaryActionLabel = "Register Past Game",
                        onSecondaryAction = onRegisterGame,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (openGames.isNotEmpty()) {
                        item(key = "header-in-progress") {
                            PlayfulSectionHeader(title = "In Progress", icon = Icons.Filled.Sensors)
                        }
                        items(openGames, key = { it.game.id }) { game ->
                            SwipeToDeleteBox(
                                onDelete = {
                                    scope.launch { container.gameDao.deleteGame(game.game) }
                                },
                            ) {
                                GameRow(game = game, onClick = { onOpenGame(game.game.id) })
                            }
                        }
                    }
                    if (closedGames.isNotEmpty()) {
                        item(key = "header-history") {
                            PlayfulSectionHeader(title = "History", icon = Icons.Filled.History)
                        }
                        items(closedGames, key = { it.game.id }) { game ->
                            SwipeToDeleteBox(
                                onDelete = {
                                    scope.launch { container.gameDao.deleteGame(game.game) }
                                },
                            ) {
                                GameRow(game = game, onClick = { onOpenDetail(game.game.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameRow(game: GameWithDetails, onClick: () -> Unit) {
    CardTile(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(
                name = game.game.title,
                icon = if (game.isOpen) Icons.Filled.Style else Icons.Filled.Verified,
                size = 46.dp,
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = game.game.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Bounded so two badges plus a long-named winner can't
                    // squeeze the title toward zero width; the chips ellipsize
                    // inside whatever they get.
                    Row(
                        modifier = Modifier.widthIn(max = 190.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Sky reads as informational next to the teal/plum/amber
                        // result badges.
                        if (game.isEdited) {
                            StatusChip(text = "Edited", icon = Icons.Filled.Edit, color = ThemeColors.sky)
                        }
                        StatusBadge(game)
                    }
                }

                Text(
                    text = scoreSummary(game),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CaptionLabel(
                        text = GameFormatting.dateTime(
                            game.game.createdAt,
                            dateOnly = game.game.playedDateOnly,
                        ),
                        icon = Icons.Filled.Event,
                    )
                    val place = game.game.locationName
                    if (place != null) {
                        CaptionLabel(
                            text = place,
                            icon = Icons.Filled.Place,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    val target = game.game.targetPoints
                    if (game.game.hasTarget && target != null) {
                        CaptionLabel(text = "to $target", icon = Icons.Filled.SportsScore)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(game: GameWithDetails) {
    val leader = game.leader
    when {
        game.isOpen -> StatusChip(text = "Live", icon = Icons.Filled.Sensors, color = ThemeColors.teal)
        game.isDraw -> StatusChip(text = "Draw", icon = Icons.Filled.DragHandle, color = ThemeColors.plum)
        leader != null -> StatusChip(
            text = leader.displayName,
            icon = Icons.Filled.EmojiEvents,
            color = ThemeColors.amber,
        )
    }
}

@Composable
private fun StatusChip(text: String, icon: ImageVector, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.15f)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RowScope.CaptionLabel(text: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun scoreSummary(game: GameWithDetails): String {
    val parts = game.rankedParticipants.map { "${it.displayName} ${it.totalScore}" }
    return if (parts.isEmpty()) "No participants" else parts.joinToString(" · ")
}
