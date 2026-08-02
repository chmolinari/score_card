// Screen for registering a game that was played outside the app: pick the
// game name and competitors exactly as in New Game, then enter each
// competitor's final total, when the game was played (date and time each
// optional), and (optionally) where. Saving creates an already-closed game
// backdated to the played-on date — no seating, target, or geolocation, since
// a transcription can't know those. Port of the iOS RegisterGameView.

// SelectableDates is still behind the experimental marker in Material 3;
// opting in file-wide keeps the build green across library releases.
@file:OptIn(ExperimentalMaterial3Api::class)

package com.christianmolinari.scorecard.ui.games

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.domain.CompetitorSelectionRules
import com.christianmolinari.scorecard.domain.GameCompetitor
import com.christianmolinari.scorecard.domain.GameRegistration
import com.christianmolinari.scorecard.domain.NameComparator
import com.christianmolinari.scorecard.domain.resolveCompetitors
import com.christianmolinari.scorecard.ui.components.AppBackground
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.LocalDateStateSaver
import com.christianmolinari.scorecard.ui.components.LocalTimeStateSaver
import com.christianmolinari.scorecard.ui.components.StringListStateSaver
import com.christianmolinari.scorecard.ui.components.GameNameEditDialog
import com.christianmolinari.scorecard.ui.components.PlayerEditDialog
import com.christianmolinari.scorecard.ui.components.PlayfulSectionHeader
import com.christianmolinari.scorecard.ui.components.TeamEditDialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

@Composable
fun RegisterGameScreen(container: AppContainer, onSaved: () -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()

    val rawPlayers by container.playerDao.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val rawTeams by container.teamDao.observeAllWithMembers()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val gameNames by container.gameNameDao.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // All games, only to rank the most-used players/teams below.
    val games by container.gameDao.observeAllWithDetails()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // The selectors list players and teams alphabetically, like the iOS @Query sort.
    val players = remember(rawPlayers) {
        rawPlayers.sortedWith(compareBy(NameComparator) { it.name })
    }
    val teams = remember(rawTeams) {
        rawTeams.sortedWith(compareBy(NameComparator) { it.team.name })
    }

    var selectedGameNameId by remember { mutableStateOf<Long?>(null) }
    var selectedCompetitors by remember { mutableStateOf(listOf<GameCompetitor>()) }

    var isAddingPlayer by remember { mutableStateOf(false) }
    var isAddingTeam by remember { mutableStateOf(false) }
    var isAddingGameName by remember { mutableStateOf(false) }
    // Set when the user taps Next; drives the move to the details step.
    var draft by remember { mutableStateOf<GameDraft?>(null) }

    // Seed the name list from existing games the first time ever, then
    // pre-select the most recently used name — exactly like New Game.
    LaunchedEffect(Unit) {
        if (selectedGameNameId == null) {
            selectedGameNameId = prepareGameNameSelection(container)
        }
    }

    val selectedGameName = gameNames.firstOrNull { it.id == selectedGameNameId }
    val resolvedCompetitors = resolveCompetitors(selectedCompetitors, players, teams)
    val canProceed = selectedGameName != null && selectedCompetitors.size >= 2

    val currentDraft = draft
    if (currentDraft != null) {
        // Step 2: final scores and details. The system back button returns to
        // the form, like popping the pushed iOS details view.
        RegisterGameDetails(
            container = container,
            draft = currentDraft,
            onBack = { draft = null },
            onSaved = onSaved,
        )
        return
    }

    // Step 1: the game name and competitors, exactly as in New Game — no
    // target section (a transcription only has final totals) and no location
    // permission, since the phone's position says nothing about a past game.
    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Register Past Game") },
                    navigationIcon = {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                draft = GameDraft(
                                    title = selectedGameName?.name ?: "",
                                    hasTarget = false,
                                    targetPoints = null,
                                    competitors = resolvedCompetitors,
                                    gameNameId = selectedGameName?.id,
                                )
                            },
                            enabled = canProceed,
                        ) { Text("Next") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                gameNameSelectionSection(
                    gameNames = gameNames,
                    selectedGameNameId = selectedGameNameId,
                    onSelect = { selectedGameNameId = it },
                    onDelete = { gameName ->
                        if (selectedGameNameId == gameName.id) selectedGameNameId = null
                        scope.launch { container.gameNameDao.delete(gameName) }
                    },
                    onAddNew = { isAddingGameName = true },
                )

                competitorSelectionSections(
                    players = players,
                    teams = teams,
                    games = games,
                    selectedCompetitors = selectedCompetitors,
                    onToggle = { competitor ->
                        selectedCompetitors =
                            CompetitorSelectionRules.toggling(competitor, selectedCompetitors)
                    },
                    onAddPlayer = { isAddingPlayer = true },
                    onAddTeam = { isAddingTeam = true },
                )

                playingSummarySection(resolvedCompetitors)
            }
        }
    }

    if (isAddingPlayer) {
        PlayerEditDialog(
            container = container,
            existing = null,
            onDismiss = { isAddingPlayer = false },
            onCreated = { newPlayer ->
                selectedCompetitors = CompetitorSelectionRules.adding(
                    GameCompetitor.PlayerCompetitor(newPlayer), selectedCompetitors
                )
            },
        )
    }
    if (isAddingTeam) {
        TeamEditDialog(
            container = container,
            existing = null,
            onDismiss = { isAddingTeam = false },
            onCreated = { newTeam ->
                selectedCompetitors = CompetitorSelectionRules.adding(
                    GameCompetitor.TeamCompetitor(newTeam), selectedCompetitors
                )
            },
        )
    }
    if (isAddingGameName) {
        GameNameEditDialog(
            container = container,
            existing = null,
            onDismiss = { isAddingGameName = false },
            onCreated = { newName -> selectedGameNameId = newName.id },
        )
    }
}

// Step 2: final scores, played-on date/time (each optional), and an optional
// location for the frozen draft. Scores are index-aligned with the draft's
// competitors, in the order shown on the previous step.
@Composable
private fun RegisterGameDetails(
    container: AppContainer,
    draft: GameDraft,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Raw text per competitor so partial input ("-", empty) survives typing.
    val allowNegativeScores by container.prefs.allowNegativeScores
        .collectAsStateWithLifecycle(initialValue = false)
    // Saveable: this screen transcribes a paper score sheet, so it is the one
    // most likely to be interrupted — rotated, or backgrounded to look at a
    // photo of the sheet and killed. Losing the typed totals would mean
    // re-entering the whole game.
    var scoreTexts by rememberSaveable(stateSaver = StringListStateSaver) {
        mutableStateOf(List(draft.competitors.size) { "" })
    }
    var hasDate by rememberSaveable { mutableStateOf(true) }
    var hasTime by rememberSaveable { mutableStateOf(false) }
    var playedDate by rememberSaveable(stateSaver = LocalDateStateSaver) {
        mutableStateOf(LocalDate.now())
    }
    var playedTime by rememberSaveable(stateSaver = LocalTimeStateSaver) {
        mutableStateOf(LocalTime.now().withSecond(0).withNano(0))
    }
    var locationText by rememberSaveable { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val scores = scoreTexts.map { it.toIntOrNull() }
    val canSave = scores.none { it == null } && !isSaving

    fun save() {
        if (isSaving) return
        isSaving = true
        scope.launch {
            val now = Instant.now()
            val playedAt = GameRegistration.playedInstant(
                date = if (hasDate) playedDate else null,
                time = if (hasDate && hasTime) playedTime else null,
                zone = ZoneId.systemDefault(),
                now = now,
            )
            val gameDao = container.gameDao
            val gameNameDao = container.gameNameDao

            // Remember this as the most recently used name so it pre-selects next time.
            draft.gameNameId?.let { nameId ->
                gameNameDao.getAll().firstOrNull { it.id == nameId }?.let { name ->
                    gameNameDao.update(name.copy(lastUsedAt = now))
                }
            }

            val gameId = gameDao.insertGame(
                GameRegistration.game(
                    title = draft.title,
                    playedAt = playedAt,
                    locationName = locationText,
                    // A date with no time is the only case whose time of day is
                    // unknown; with no date at all the stamp is "now".
                    playedDateOnly = hasDate && !hasTime,
                )
            )
            draft.competitors.forEachIndexed { index, competitor ->
                val participantId = gameDao.insertParticipant(
                    GameRegistration.participant(gameId, competitor, index)
                )
                gameDao.insertScoreEntry(
                    GameRegistration.finalScoreEntry(
                        participantId = participantId,
                        points = requireNotNull(scores[index]),
                        playedAt = playedAt,
                        allowNegativeScores = allowNegativeScores,
                    )
                )
            }
            onSaved()
        }
    }

    BackHandler(enabled = !isSaving) { onBack() }
    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(draft.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack, enabled = !isSaving) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        TextButton(onClick = { save() }, enabled = canSave) { Text("Save Game") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { PlayfulSectionHeader("Final Scores") }
                items(draft.competitors.size) { index ->
                    CardTile(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(draft.competitors[index].name, modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = scoreTexts[index],
                                onValueChange = { text ->
                                    // A leading minus is only accepted when
                                    // below-zero totals are allowed; the IME
                                    // shows a number pad, the filter is what
                                    // actually guards the input.
                                    val pattern = if (allowNegativeScores) "-?\\d*" else "\\d*"
                                    if (text.matches(Regex(pattern))) {
                                        scoreTexts = scoreTexts.toMutableList()
                                            .also { it[index] = text }
                                    }
                                },
                                label = { Text("Score") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(120.dp),
                            )
                        }
                    }
                }
                item {
                    FooterText(
                        if (allowNegativeScores) "Enter each competitor's final total."
                        else "Enter each competitor's final total. Totals stop at zero."
                    )
                }

                item { PlayfulSectionHeader("Played On") }
                item {
                    CardTile(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Set the date", modifier = Modifier.weight(1f))
                            Switch(checked = hasDate, onCheckedChange = { hasDate = it })
                        }
                        if (hasDate) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Played on", modifier = Modifier.weight(1f))
                                TextButton(onClick = { showDatePicker = true }) {
                                    Text(
                                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                                            .format(playedDate)
                                    )
                                }
                                if (hasTime) {
                                    TextButton(onClick = { showTimePicker = true }) {
                                        Text(
                                            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                                                .format(playedTime)
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Set the time", modifier = Modifier.weight(1f))
                                Switch(checked = hasTime, onCheckedChange = { hasTime = it })
                            }
                        }
                    }
                }
                item {
                    FooterText(
                        when {
                            !hasDate -> "Without a date, the game is filed in History under today."
                            !hasTime ->
                                "The game is filed in History under this date, without a time of day."
                            else -> "The game is filed in History under this date and time."
                        }
                    )
                }

                item { PlayfulSectionHeader("Location") }
                item {
                    OutlinedTextField(
                        value = locationText,
                        onValueChange = { locationText = it },
                        label = { Text("Location (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        // The date picker works in UTC-midnight millis; conversion in and out
        // therefore goes through ZoneOffset.UTC, not the system zone. Future
        // dates aren't selectable — a past game can't have been played then.
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis =
                playedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                        .isAfter(LocalDate.now())

                override fun isSelectableYear(year: Int): Boolean = year <= LocalDate.now().year
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        playedDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        // Material 3 has no TimePickerDialog composable yet; the standard
        // pattern is a TimePicker inside an AlertDialog.
        val timePickerState = rememberTimePickerState(
            initialHour = playedTime.hour,
            initialMinute = playedTime.minute,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Played at") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    playedTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
        )
    }
}
