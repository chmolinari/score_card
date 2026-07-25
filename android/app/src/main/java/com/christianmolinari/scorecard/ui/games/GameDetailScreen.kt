// Read-only history detail for a finished game: final standings, metadata, and
// an optional place line for where it was played. The iOS app shows a map here;
// on Android we show the resolved place name instead — a deliberate adaptation
// to avoid pulling in a maps dependency.

package com.christianmolinari.scorecard.ui.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.data.db.GameWithDetails
import com.christianmolinari.scorecard.data.db.ParticipantWithDetails
import com.christianmolinari.scorecard.domain.displayName
import com.christianmolinari.scorecard.domain.isDraw
import com.christianmolinari.scorecard.domain.isEdited
import com.christianmolinari.scorecard.domain.rankedParticipants
import com.christianmolinari.scorecard.domain.sortedEdits
import com.christianmolinari.scorecard.domain.subtitle
import com.christianmolinari.scorecard.domain.topScorers
import com.christianmolinari.scorecard.domain.totalScore
import com.christianmolinari.scorecard.location.LocationCapture
import com.christianmolinari.scorecard.ui.components.AppBackground
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.GameFormatting
import com.christianmolinari.scorecard.ui.components.PlayfulSectionHeader
import com.christianmolinari.scorecard.ui.theme.ThemeColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    container: AppContainer,
    gameId: Long,
    onBack: () -> Unit,
    onEditScores: (Long) -> Unit,
) {
    val game by container.database.gameDao().observeGame(gameId)
        .collectAsStateWithLifecycle(initialValue = null)

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            game?.game?.title ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Only a finished game reaches this screen; an open one
                        // is corrected by scoring on the board instead.
                        IconButton(onClick = { onEditScores(gameId) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit Scores")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            val loaded = game
            if (loaded == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                GameDetailContent(
                    game = loaded,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
        }
    }
}

@Composable
private fun GameDetailContent(game: GameWithDetails, modifier: Modifier = Modifier) {
    // IDs of the competitor(s) sharing the top score, so a tie can mark every
    // leader rather than only the first row.
    val topScorerIds = game.topScorers.map { it.participant.id }.toSet()
    val ranked = game.rankedParticipants

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PlayfulSectionHeader(
                title = if (game.isDraw) "Final Standings · Draw" else "Final Standings",
                icon = Icons.Filled.EmojiEvents,
            )
        }
        item {
            CardTile {
                ranked.forEachIndexed { index, participant ->
                    StandingRow(
                        rank = index,
                        participant = participant,
                        isTopScorer = participant.participant.id in topScorerIds,
                        isDraw = game.isDraw,
                    )
                    if (index < ranked.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }

        if (game.game.locationName != null ||
            (game.game.latitude != null && game.game.longitude != null)
        ) {
            item { PlayfulSectionHeader(title = "Location", icon = Icons.Filled.Place) }
            item { LocationCard(game) }
        }

        item { PlayfulSectionHeader(title = "Details") }
        item { GameInfoSection(game) }

        // The reason for every correction is recorded, not discarded: this is
        // why the editor insists on one before it starts.
        if (game.isEdited) {
            item { PlayfulSectionHeader(title = "Edit History", icon = Icons.Filled.Edit) }
            item {
                val edits = game.sortedEdits
                CardTile {
                    edits.forEachIndexed { index, edit ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(edit.reason, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                GameFormatting.dateTime(edit.editedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (index < edits.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StandingRow(
    rank: Int,
    participant: ParticipantWithDetails,
    isTopScorer: Boolean,
    isDraw: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StandingIcon(rank = rank, isTopScorer = isTopScorer, isDraw = isDraw)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                participant.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                participant.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${participant.totalScore}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

// Standings icon: a shared "equal" mark for every leader in a draw, a trophy
// for a sole winner, and a plain rank number for everyone else.
@Composable
private fun StandingIcon(rank: Int, isTopScorer: Boolean, isDraw: Boolean) {
    if (isDraw && isTopScorer) {
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = "Draw",
            tint = ThemeColors.plum,
            modifier = Modifier.width(28.dp),
        )
    } else if (rank == 0) {
        Icon(
            Icons.Filled.EmojiEvents,
            contentDescription = "Winner",
            tint = ThemeColors.amber,
            modifier = Modifier.width(28.dp),
        )
    } else {
        Text(
            "${rank + 1}.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
    }
}

@Composable
private fun LocationCard(game: GameWithDetails) {
    val context = LocalContext.current
    var resolvedPlace by remember(game.game.id) { mutableStateOf<String?>(null) }

    // When the game only carries coordinates, resolve a human-readable place
    // lazily; raw coordinates are never shown.
    LaunchedEffect(game.game.id, game.game.locationName) {
        val latitude = game.game.latitude
        val longitude = game.game.longitude
        if (game.game.locationName == null && latitude != null && longitude != null) {
            resolvedPlace = LocationCapture.reverseGeocode(context, latitude, longitude)
        }
    }

    CardTile {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = ThemeColors.teal,
            )
            // Same fallback as the iOS map marker: the game title stands in
            // when no place name is available.
            Text(
                game.game.locationName ?: resolvedPlace ?: game.game.title,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
