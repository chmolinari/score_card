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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
import kotlinx.coroutines.launch
import java.time.Instant

// rememberSaveable's default saver can't carry a List, so these flatten the two
// index-parallel score lists into something the saved-instance Bundle accepts.
private val IntListStateSaver = listSaver<List<Int>, Int>(save = { it }, restore = { it })
private val StringListStateSaver = listSaver<List<String>, String>(save = { it }, restore = { it })

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

    // Saveable so a rotation or a low-memory kill mid-flow doesn't throw away a
    // typed reason and every retyped total.
    var reason by rememberSaveable(game.game.id) { mutableStateOf("") }
    // Seeded verbatim from the stored totals and changed ONLY by a user action.
    //
    // Deriving these by re-normalizing every field on each recomposition would
    // apply the below-zero clamp to rows nobody touched: a competitor stored on
    // a negative total (registering a past game keeps negative finals verbatim,
    // whatever the preference says) would silently propose zero, arming Save
    // with no input and rewriting a finished score on the next tap.
    var proposedTotals by rememberSaveable(game.game.id, stateSaver = IntListStateSaver) {
        mutableStateOf(originalTotals)
    }
    var totalTexts by rememberSaveable(game.game.id, stateSaver = StringListStateSaver) {
        mutableStateOf(originalTotals.map { it.toString() })
    }
    var isEditingScores by rememberSaveable(game.game.id) { mutableStateOf(false) }
    // Latches the commit: the screen stays up while the write and the pop run,
    // so without this a second tap would append the same delta again and log a
    // second edit for one correction.
    var isSaving by remember(game.game.id) { mutableStateOf(false) }

    // Collected rather than read once, so the clamp can't be applied with a
    // stale default while the preference is still loading.
    val allowNegativeScores by container.prefs.allowNegativeScores
        .collectAsStateWithLifecycle(initialValue = false)

    val trimmedReason = reason.trim()
    val canContinue = trimmedReason.isNotEmpty()
    val hasChanges = GameScoreEdit.isChanged(before = originalTotals, after = proposedTotals)

    fun setTotal(index: Int, value: Int) {
        val normalized = GameScoreEdit.normalizedTotal(value, allowNegativeScores)
        proposedTotals = proposedTotals.toMutableList().also { it[index] = normalized }
        totalTexts = totalTexts.toMutableList().also { it[index] = normalized.toString() }
    }

    // Typed totals reach the state per keystroke rather than when the field
    // loses focus: Save lives in the app bar and tapping it doesn't move focus
    // first, so a parse-on-blur field would let Save commit a stale total —
    // silently dropping what was just typed while still recording an edit
    // claiming the score was corrected.
    fun onTextChange(index: Int, text: String) {
        totalTexts = totalTexts.toMutableList().also { it[index] = text }
        proposedTotals = proposedTotals.toMutableList().also {
            it[index] = GameScoreEdit.typedTotal(
                text = text,
                fallback = originalTotals[index],
                allowNegative = allowNegativeScores,
            )
        }
    }

    // Re-render the field from the total that will actually be stored once it
    // loses focus, so a clamped or half-typed entry ("-5" with below-zero off,
    // or an emptied field) stops showing a number the game will not store.
    fun onFieldCommitted(index: Int) {
        totalTexts = totalTexts.toMutableList().also { it[index] = proposedTotals[index].toString() }
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

    // Always intercept. Leaving the handler disabled during the save would let
    // Back fall through to the NavController, popping this screen and
    // cancelling the coroutine scope the write is running in — the opposite of
    // what guarding on isSaving is for.
    BackHandler {
        if (isSaving) return@BackHandler
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
                    onTextChange = ::onTextChange,
                    onFieldCommitted = ::onFieldCommitted,
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
    onFieldCommitted: (Int) -> Unit,
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
                            // A leading minus is only accepted when below-zero
                            // totals are allowed. The IME shows a number pad;
                            // the filter is what actually guards the input.
                            val pattern = if (allowNegativeScores) "-?\\d*" else "\\d*"
                            if (text.matches(Regex(pattern))) onTextChange(index, text)
                        },
                        label = { Text("Total") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .width(110.dp)
                            .onFocusChanged { if (!it.isFocused) onFieldCommitted(index) },
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
