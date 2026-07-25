// Correcting a closed game's scores. Two steps: first the reason for the edit —
// mandatory, because a finished result is a record and a change to it has to
// stay accountable — then the scores themselves. Nothing else about the game is
// editable here, and an edit never reopens the game. Port of the iOS
// GameEditView; behaviour is specified in docs/game-editing.md.

package com.christianmolinari.scorecard.ui.games

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.data.db.GameWithDetails
import com.christianmolinari.scorecard.domain.GameScoreEdit
import com.christianmolinari.scorecard.domain.displayName
import com.christianmolinari.scorecard.domain.rankedParticipants
import com.christianmolinari.scorecard.domain.subtitle
import com.christianmolinari.scorecard.domain.totalScore
import com.christianmolinari.scorecard.ui.components.AppBackground
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.PlayfulSectionHeader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun GameEditScreen(
    container: AppContainer,
    gameId: Long,
    onDone: () -> Unit,
) {
    val game by container.database.gameDao().observeGame(gameId)
        .collectAsStateWithLifecycle(initialValue = null)

    val loaded = game
    if (loaded == null) {
        AppBackground {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    } else {
        GameEditContent(container = container, game = loaded, onDone = onDone)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameEditContent(
    container: AppContainer,
    game: GameWithDetails,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Competitors and their totals are frozen when the editor opens, keyed by
    // the game's id. The rows are laid out in ranking order, and re-reading the
    // ranking per recomposition would re-sort them under the user's cursor as
    // soon as a total is typed. The snapshot is also the "before" side of every
    // delta and of the did-anything-change check.
    val participants = remember(game.game.id) { game.rankedParticipants }
    val originalTotals = remember(game.game.id) { participants.map { it.totalScore } }

    var reason by remember(game.game.id) { mutableStateOf("") }
    var totalTexts by remember(game.game.id) {
        mutableStateOf(originalTotals.map { it.toString() })
    }
    var isEditingScores by remember(game.game.id) { mutableStateOf(false) }
    // Latches the commit: the screen stays up while the write and the pop run,
    // so without this a second tap would append the same delta again and log a
    // second edit for one correction.
    var isSaving by remember(game.game.id) { mutableStateOf(false) }

    var allowNegativeScores by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        allowNegativeScores = container.prefs.allowNegativeScores.first()
    }

    // Parsed per keystroke rather than when a field loses focus: Save lives in
    // the app bar and tapping it doesn't move focus first, so a parse-on-blur
    // field would let Save commit a stale total — silently dropping what was
    // just typed while still recording an edit claiming the score was
    // corrected. An empty or half-typed field ("", "-") reads as "unchanged"
    // rather than as zero, so clearing a total never arms Save on its own.
    val proposedTotals = totalTexts.mapIndexed { index, text ->
        GameScoreEdit.normalizedTotal(
            requested = text.toIntOrNull() ?: originalTotals[index],
            allowNegative = allowNegativeScores,
        )
    }

    val trimmedReason = reason.trim()
    val canContinue = trimmedReason.isNotEmpty()
    val hasChanges = GameScoreEdit.isChanged(before = originalTotals, after = proposedTotals)

    fun setTotal(index: Int, value: Int) {
        val normalized = GameScoreEdit.normalizedTotal(value, allowNegativeScores)
        totalTexts = totalTexts.toMutableList().also { it[index] = normalized.toString() }
    }

    fun save() {
        if (isSaving || !hasChanges) return
        isSaving = true
        scope.launch {
            val plan = GameScoreEdit.plan(
                gameId = game.game.id,
                participantIds = participants.map { it.participant.id },
                originalTotals = originalTotals,
                proposedTotals = proposedTotals,
                reason = trimmedReason,
                editedAt = Instant.now(),
            )
            // Null means nothing should be written — the rule lives in the
            // planner, so this is the same refusal the Save button already
            // makes rather than a second, drifting copy of it.
            if (plan != null) {
                container.database.gameDao().applyScoreEdit(plan.entries, plan.edit)
            }
            onDone()
        }
    }

    BackHandler(enabled = !isSaving) {
        if (isEditingScores) isEditingScores = false else onDone()
    }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (isEditingScores) "Edit Scores" else "Reason for Edit",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { if (isEditingScores) isEditingScores = false else onDone() },
                            enabled = !isSaving,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (isEditingScores) {
                            TextButton(onClick = { save() }, enabled = hasChanges && !isSaving) {
                                Text("Save")
                            }
                        } else {
                            TextButton(
                                onClick = { isEditingScores = true },
                                enabled = canContinue,
                            ) {
                                Text("Continue")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            if (isEditingScores) {
                ScoresStep(
                    participants = participants.map { it.displayName to it.subtitle },
                    totalTexts = totalTexts,
                    allowNegativeScores = allowNegativeScores,
                    onTextChange = { index, text ->
                        totalTexts = totalTexts.toMutableList().also { it[index] = text }
                    },
                    onStep = { index, step ->
                        setTotal(index, proposedTotals[index] + step)
                    },
                    modifier = Modifier.padding(padding),
                )
            } else {
                ReasonStep(
                    reason = reason,
                    onReasonChange = { reason = it },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun ReasonStep(
    reason: String,
    onReasonChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { PlayfulSectionHeader("Reason") }
        item {
            CardTile(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    label = { Text("Why are these scores being changed?") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            FooterText("Required. It is kept with the game and shown in its edit history.")
        }
    }
}

@Composable
private fun ScoresStep(
    participants: List<Pair<String, String>>,
    totalTexts: List<String>,
    allowNegativeScores: Boolean,
    onTextChange: (Int, String) -> Unit,
    onStep: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { PlayfulSectionHeader("Final Scores") }
        items(participants.size) { index ->
            val (name, subtitle) = participants[index]
            CardTile(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onStep(index, -1) }) {
                        Icon(Icons.Filled.Remove, contentDescription = "Lower $name's total")
                    }
                    OutlinedTextField(
                        value = totalTexts[index],
                        onValueChange = { text ->
                            // Digits with an optional leading minus, like the
                            // Register Past Game score fields; the IME shows a
                            // number pad, the filter is what actually guards it.
                            if (text.matches(Regex("-?\\d*"))) onTextChange(index, text)
                        },
                        label = { Text("Total") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                    IconButton(onClick = { onStep(index, 1) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Raise $name's total")
                    }
                }
            }
        }
        item {
            FooterText(
                if (allowNegativeScores) "Only the scores can be changed."
                else "Only the scores can be changed. Totals stop at zero."
            )
        }
    }
}
