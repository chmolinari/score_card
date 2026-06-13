// TopAppBar and ModalBottomSheet-adjacent APIs sit behind the experimental
// marker in Material 3; opting in file-wide keeps the build green across
// library releases.
@file:OptIn(ExperimentalMaterial3Api::class)

package com.christianmolinari.scorecard.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.data.db.GameWithDetails
import com.christianmolinari.scorecard.data.db.ParticipantWithDetails
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.ScoreEntryEntity
import com.christianmolinari.scorecard.data.db.SeatEntity
import com.christianmolinari.scorecard.domain.DealingDirection
import com.christianmolinari.scorecard.domain.advancedDealerIndex
import com.christianmolinari.scorecard.domain.currentDealer
import com.christianmolinari.scorecard.domain.displayName
import com.christianmolinari.scorecard.domain.isTeamCompetitor
import com.christianmolinari.scorecard.domain.nextDealer
import com.christianmolinari.scorecard.domain.participantsInDealingOrder
import com.christianmolinari.scorecard.domain.sortedMembers
import com.christianmolinari.scorecard.domain.subtitle
import com.christianmolinari.scorecard.ui.components.AppBackground
import com.christianmolinari.scorecard.ui.components.Avatar
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.PlayfulSectionHeader
import com.christianmolinari.scorecard.ui.theme.ThemeColors
import java.time.Instant
import kotlinx.coroutines.launch

// Live scoreboard for an open game. Add points to each competitor, undo the
// last entry, watch target progress, and close the game when it's done.
//
// Rows are ordered by the dealing rotation — NOT by score — so they don't
// shuffle around during play.

// One competitor's precomputed numbers for a render pass.
private data class ScoreboardRowData(
    val participant: ParticipantWithDetails,
    val score: Int,
    val entryCount: Int,
)

@Composable
fun ScoreboardScreen(container: AppContainer, gameId: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val gameDao = container.database.gameDao()

    val gameFlow = remember(gameId) { gameDao.observeGame(gameId) }
    val game by gameFlow.collectAsStateWithLifecycle(initialValue = null)
    val direction by container.prefs.dealingDirection
        .collectAsStateWithLifecycle(initialValue = DealingDirection.CounterClockwise)

    // Total score entries the game had at the start of the current hand. "Next
    // Hand" stays disabled until at least one point is scored beyond this, then
    // is reset back to the current total when the hand is advanced — so it's
    // immediately disabled again until the next score lands. -1 means "not yet
    // armed": it is set exactly once, from the first non-null game emission, so
    // entries persisted in earlier sessions don't count as scored this hand.
    var handBaseline by rememberSaveable { mutableIntStateOf(-1) }

    // Set when the user declines the end-of-game prompt; keeps the board
    // locked (and the prompt quiet) until the over-target score is corrected
    // back down.
    var declinedTargetEnd by rememberSaveable { mutableStateOf(false) }
    var showTargetPrompt by remember { mutableStateOf(false) }

    // Previous reached-target state, used to fire the prompt only on a genuine
    // false->true transition. null means "not yet observed" — mirrors iOS's
    // two-parameter .onChange, which never runs for the initial value, so a
    // game opened already at/over the target shows the banner and the lock but
    // not the end-game dialog (it only appears when the target is crossed
    // anew). Plain remember (not Saveable): after process death we want to
    // re-baseline silently rather than re-pop the dialog.
    var prevReachedTarget by remember { mutableStateOf<Boolean?>(null) }

    var showCloseConfirmation by remember { mutableStateOf(false) }
    var showSeatingSetup by remember { mutableStateOf(false) }
    var seatingSaving by remember { mutableStateOf(false) }

    // The sheet tracks the participant's id, not the object: Room emits fresh
    // relation instances on every change, so we look up the live one each
    // composition (the iOS sheet binds the live @Model object instead).
    var scoringParticipantId by remember { mutableStateOf<Long?>(null) }

    // Everything the rows, banner, and dealer section need is derived ONCE here
    // in a single pass over the competitors. Each competitor's entries are
    // summed exactly once, then plain Int/Bool values are handed down — so a
    // score tap doesn't re-sum and re-sort per row. (Unlike SwiftData, the Room
    // flow re-emits after every insert, so no manual invalidation is needed.)
    val current = game
    val rows = current?.participantsInDealingOrder(direction)?.map { participant ->
        val entries = participant.entries
        ScoreboardRowData(participant, entries.sumOf { it.points }, entries.size)
    } ?: emptyList()
    val target = if (current?.game?.hasTarget == true) current.game.targetPoints else null
    val reachedTarget = target != null && rows.any { it.score >= target }
    val totalEntryCount = rows.sumOf { it.entryCount }
    // A point scored this hand closes the quick-add buttons and arms Next Hand.
    val scoredThisHand = handBaseline >= 0 && totalEntryCount > handBaseline
    val scoringDisabled = scoredThisHand || reachedTarget
    val winnerNames = if (target != null) {
        rows.filter { it.score >= target }.joinToString(", ") { it.participant.displayName }
    } else {
        ""
    }

    // Arm the per-hand baseline from the game's total entry count on FIRST
    // load only; later emissions (each score) must not move it.
    LaunchedEffect(current) {
        if (handBaseline == -1 && current != null) {
            handBaseline = totalEntryCount
        }
    }

    // Only nudge once per time the target is crossed; re-arm for the next
    // crossing once the situation has been resolved. We act solely on real
    // transitions: the pre-load (null game) state is ignored, and the first
    // loaded state is taken as the baseline without prompting — so re-entering
    // the scoreboard, rotating, or returning after process death never re-pops
    // the dialog for a score that was already over the target.
    LaunchedEffect(reachedTarget, current != null) {
        if (current == null) return@LaunchedEffect
        val prev = prevReachedTarget
        prevReachedTarget = reachedTarget
        if (prev == null || prev == reachedTarget) return@LaunchedEffect
        if (reachedTarget) {
            if (!declinedTargetEnd) showTargetPrompt = true
        } else {
            declinedTargetEnd = false
        }
    }

    fun addScore(participant: ParticipantWithDetails, points: Int) {
        scope.launch {
            gameDao.insertScoreEntry(
                ScoreEntryEntity(
                    participantId = participant.participant.id,
                    points = points,
                    timestamp = Instant.now(),
                )
            )
        }
    }

    // Pass the deal to the next player, bump the hand counter, and re-arm the
    // baseline so the button disables again until the next point is scored.
    fun advanceHand() {
        val g = current ?: return
        handBaseline = totalEntryCount
        scope.launch {
            gameDao.updateGame(
                g.game.copy(
                    currentDealerIndex = g.advancedDealerIndex(direction),
                    currentHand = g.game.currentHand + 1,
                )
            )
        }
    }

    fun closeGame() {
        val g = current ?: return
        scope.launch {
            gameDao.updateGame(g.game.copy(closedAt = Instant.now()))
            onBack()
        }
    }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(current?.game?.title ?: "") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = { showCloseConfirmation = true }) {
                            Text("End Game", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { innerPadding ->
            if (current != null) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (target != null && reachedTarget) {
                        item(key = "target-banner") {
                            TargetReachedBanner(names = winnerNames, target = target)
                        }
                    }

                    item(key = "hand-header") {
                        PlayfulSectionHeader(title = "Current Hand", icon = Icons.Filled.PanTool)
                    }
                    item(key = "dealer-card") {
                        DealerCard(
                            game = current,
                            direction = direction,
                            canAdvance = scoredThisHand,
                            reached = reachedTarget,
                            onAdvance = { advanceHand() },
                            onSetUpSeating = { showSeatingSetup = true },
                        )
                    }

                    item(key = "scores-header") {
                        PlayfulSectionHeader(title = "Scores", icon = Icons.Filled.FormatListNumbered)
                    }
                    itemsIndexed(
                        rows,
                        key = { _, row -> "row-${row.participant.participant.id}" },
                    ) { index, row ->
                        ScoreboardRow(
                            position = index + 1,
                            participant = row.participant,
                            total = row.score,
                            target = target,
                            scoringDisabled = scoringDisabled,
                            onScore = { points -> addScore(row.participant, points) },
                            onTapMore = { scoringParticipantId = row.participant.participant.id },
                        )
                    }

                    item(key = "details-header") {
                        PlayfulSectionHeader(title = "Details", icon = Icons.Filled.Info)
                    }
                    item(key = "details") {
                        GameInfoSection(game = current)
                    }
                }
            }
        }
    }

    if (showCloseConfirmation) {
        AlertDialog(
            onDismissRequest = { showCloseConfirmation = false },
            title = { Text("End this game?") },
            text = { Text("The final scores will be saved to your history. You can't add more points after ending.") },
            confirmButton = {
                TextButton(onClick = {
                    showCloseConfirmation = false
                    closeGame()
                }) {
                    Text("End Game", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirmation = false }) {
                    Text("Keep Playing")
                }
            },
        )
    }

    // Shown once when a competitor first reaches the target, inviting the user
    // to end the game. Declining (or dismissing) locks scoring until the
    // over-target score is corrected back down.
    if (showTargetPrompt) {
        AlertDialog(
            onDismissRequest = {
                showTargetPrompt = false
                declinedTargetEnd = true
            },
            title = { Text("End the game?") },
            text = {
                Text(
                    "$winnerNames reached the ${current?.game?.targetPoints ?: 0}-point target. " +
                        "End the game and record the result? If the score was added by mistake, " +
                        "choose Not Yet and undo the last score to keep playing."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTargetPrompt = false
                    closeGame()
                }) {
                    Text("End Game", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTargetPrompt = false
                    declinedTargetEnd = true
                }) {
                    Text("Not Yet")
                }
            },
        )
    }

    val scoringParticipant = scoringParticipantId?.let { id ->
        current?.participants?.firstOrNull { it.participant.id == id }
    }
    if (scoringParticipant != null) {
        ParticipantScoringSheet(
            container = container,
            participant = scoringParticipant,
            onDismiss = { scoringParticipantId = null },
        )
    }

    // Full-screen seating setup for games that were created without one (or
    // before seating existed). Replaces the seats and restarts the rotation.
    if (showSeatingSetup && current != null) {
        Dialog(
            onDismissRequest = { if (!seatingSaving) showSeatingSetup = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            AppBackground {
                Scaffold(
                    containerColor = Color.Transparent,
                    topBar = {
                        TopAppBar(
                            title = { Text("Seating & Dealer") },
                            navigationIcon = {
                                IconButton(
                                    onClick = { showSeatingSetup = false },
                                    enabled = !seatingSaving,
                                ) {
                                    Icon(imageVector = Icons.Filled.Close, contentDescription = "Cancel")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        )
                    },
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        SeatingArrangement(
                            people = peopleForSeating(current),
                            direction = direction,
                            confirmTitle = "Save",
                            isSaving = seatingSaving,
                        ) { ordered ->
                            seatingSaving = true
                            scope.launch {
                                gameDao.deleteSeatsForGame(current.game.id)
                                ordered.forEachIndexed { position, player ->
                                    gameDao.insertSeat(
                                        SeatEntity(
                                            gameId = current.game.id,
                                            playerId = player.id,
                                            position = position,
                                        )
                                    )
                                }
                                gameDao.updateGame(
                                    current.game.copy(currentDealerIndex = 0, currentHand = 1)
                                )
                                seatingSaving = false
                                showSeatingSetup = false
                            }
                        }
                    }
                }
            }
        }
    }
}

// Individual people at the table, derived from the competitors (teams expanded
// to members), for setting up seating on an existing game.
private fun peopleForSeating(game: GameWithDetails): List<PlayerEntity> {
    val seen = mutableSetOf<Long>()
    val result = mutableListOf<PlayerEntity>()
    fun add(player: PlayerEntity) {
        if (seen.add(player.id)) result.add(player)
    }
    for (participant in game.participants.sortedBy { it.participant.sortIndex }) {
        val player = participant.player
        if (player != null) {
            add(player)
        } else {
            participant.team?.sortedMembers?.forEach { add(it) }
        }
    }
    return result
}

// Banner shown while a competitor sits at/over the target; scoring is locked
// until the game ends or the score is corrected.
@Composable
private fun TargetReachedBanner(names: String, target: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(ThemeColors.amber, ThemeColors.coral)))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Flag,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(36.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Target reached!",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = "$names hit $target points. End the game to record the result, " +
                    "or undo the last score to fix a mistake and keep playing.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

// The current-hand card: who deals, the hand counter, and the two ways to pass
// the deal. Falls back to a seating-setup button when no seating exists yet.
@Composable
private fun DealerCard(
    game: GameWithDetails,
    direction: DealingDirection,
    canAdvance: Boolean,
    reached: Boolean,
    onAdvance: () -> Unit,
    onSetUpSeating: () -> Unit,
) {
    val dealer = game.currentDealer
    if (dealer != null) {
        CardTile(modifier = Modifier.fillMaxWidth()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(name = dealer.name, icon = Icons.Filled.PanTool, size = 44.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Dealer this hand",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = dealer.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "HAND",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${game.game.currentHand}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = ThemeColors.accent,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onAdvance,
                enabled = canAdvance && !reached,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Next Hand", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))

            // A drawn hand scores nothing, so "Next Hand" stays disabled. This
            // passes the deal anyway — only meaningful before any point is
            // scored this hand (otherwise it wasn't a draw).
            OutlinedButton(
                onClick = onAdvance,
                enabled = !canAdvance && !reached,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Hand Was a Draw")
            }
            Spacer(modifier = Modifier.height(12.dp))

            val next = game.nextDealer(direction)
            if (next != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Next to deal: ${next.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = if (canAdvance) {
                    "The deal passes ${direction.adverb}. Tap Next Hand when this hand is done."
                } else {
                    "Score this hand to pass the deal, or mark it a draw if no one scored."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    } else {
        CardTile(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSetUpSeating, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Filled.Groups,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Set Up Seating & Dealer", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// A single competitor row on the scoreboard, with inline quick-add buttons.
// `total` is precomputed by the parent so the row doesn't re-sum the entries.
@Composable
private fun ScoreboardRow(
    // 1-based place in the fixed table order (1 = first dealer), not a score rank.
    position: Int,
    participant: ParticipantWithDetails,
    total: Int,
    target: Int?,
    // True when the inline quick-add buttons are closed (a point was already
    // scored this hand, or the target's been reached). The ellipsis stays live.
    scoringDisabled: Boolean,
    onScore: (Int) -> Unit,
    onTapMore: () -> Unit,
) {
    val reachedTarget = target != null && total >= target
    CardTile(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Avatar(
                    name = participant.displayName,
                    icon = if (participant.isTeamCompetitor) Icons.Filled.Group else null,
                    size = 46.dp,
                )
                PositionBadge(position = position, modifier = Modifier.align(Alignment.BottomEnd))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = participant.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = participant.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "$total",
                fontSize = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (reachedTarget) ThemeColors.amber else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // The per-row action area. The quick-add buttons close after a point is
        // scored this hand (reopened by Next Hand) and while the target is
        // reached; the ellipsis always stays live so the detail sheet — where
        // scores are corrected — remains reachable.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3, 5).forEach { amount ->
                OutlinedButton(
                    onClick = { onScore(amount) },
                    enabled = !scoringDisabled,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ThemeColors.teal),
                ) {
                    Text("+$amount")
                }
            }
            OutlinedButton(
                onClick = onTapMore,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreHoriz,
                    contentDescription = "More scoring options",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// Neutral table-order badge (the list is in dealing order, not by score),
// tucked onto the avatar.
@Composable
private fun PositionBadge(position: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(ThemeColors.plum)
            .border(width = 2.dp, color = ThemeColors.cardSurface, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$position",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
