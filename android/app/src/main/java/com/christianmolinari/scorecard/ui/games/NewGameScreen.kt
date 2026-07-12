package com.christianmolinari.scorecard.ui.games

// Screen for starting a new game: name it, optionally set a target score, and
// choose the competitors (individual players or teams). Players and teams can
// be created inline here without leaving the screen, and are auto-selected
// once created. On start the current date/time and (best-effort) geolocation
// are stamped onto the game. The name and competitor selectors are shared
// with the Register Past Game screen (see GameCreationSections.kt).

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.data.db.GameEntity
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.SeatEntity
import com.christianmolinari.scorecard.domain.CompetitorSelectionRules
import com.christianmolinari.scorecard.domain.DealingDirection
import com.christianmolinari.scorecard.domain.GameCompetitor
import com.christianmolinari.scorecard.domain.GameRegistration
import com.christianmolinari.scorecard.domain.NameComparator
import com.christianmolinari.scorecard.domain.resolveCompetitors
import com.christianmolinari.scorecard.ui.components.AppBackground
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.GameNameEditDialog
import com.christianmolinari.scorecard.ui.components.PlayerEditDialog
import com.christianmolinari.scorecard.ui.components.TeamEditDialog
import java.time.Instant
import kotlinx.coroutines.launch

private enum class LocationStatus { NotDetermined, Granted, Denied }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGameScreen(container: AppContainer, onStarted: (Long) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()

    val rawPlayers by container.database.playerDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val rawTeams by container.database.teamDao().observeAllWithMembers()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val gameNames by container.database.gameNameDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // All games, only to rank the most-used players/teams below.
    val games by container.database.gameDao().observeAllWithDetails()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val dealingDirection by container.prefs.dealingDirection
        .collectAsStateWithLifecycle(initialValue = DealingDirection.CounterClockwise)

    // The selectors list players and teams alphabetically, like the iOS @Query sort.
    val players = remember(rawPlayers) {
        rawPlayers.sortedWith(compareBy(NameComparator) { it.name })
    }
    val teams = remember(rawTeams) {
        rawTeams.sortedWith(compareBy(NameComparator) { it.team.name })
    }

    // The chosen game name. Pre-selected to the most recently used one when the
    // screen opens; the game's title is copied from it on start.
    var selectedGameNameId by remember { mutableStateOf<Long?>(null) }
    var hasTarget by remember { mutableStateOf(false) }
    var targetPoints by remember { mutableStateOf(11) }

    // Selected competitors, in the order they were added, so the scoreboard
    // preserves that order.
    var selectedCompetitors by remember { mutableStateOf(listOf<GameCompetitor>()) }

    var isAddingPlayer by remember { mutableStateOf(false) }
    var isAddingTeam by remember { mutableStateOf(false) }
    var isAddingGameName by remember { mutableStateOf(false) }
    // Set when the user taps Next; drives the move to the seating step.
    var draft by remember { mutableStateOf<GameDraft?>(null) }
    var isStarting by remember { mutableStateOf(false) }

    // One-shot location permission request when the screen opens, mirroring
    // iOS requestAuthorizationIfNeeded. The status only feeds the footnote;
    // capture itself fails soft either way.
    var locationStatus by remember {
        mutableStateOf(
            if (container.locationCapture.hasPermission()) LocationStatus.Granted
            else LocationStatus.NotDetermined
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        locationStatus =
            if (results.values.any { it }) LocationStatus.Granted else LocationStatus.Denied
    }
    LaunchedEffect(Unit) {
        if (locationStatus != LocationStatus.Granted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    // Seed the name list from existing games the first time ever, then
    // pre-select the most recently used name. Runs each time the screen opens
    // so the default reflects the latest "last used"; the seeding is one-off.
    LaunchedEffect(Unit) {
        if (selectedGameNameId == null) {
            selectedGameNameId = prepareGameNameSelection(container)
        }
    }

    val selectedGameName = gameNames.firstOrNull { it.id == selectedGameNameId }

    // Re-resolve the selection against the freshest flow emissions so names and
    // rosters stay live (iOS gets this for free by holding the model object).
    val resolvedCompetitors = resolveCompetitors(selectedCompetitors, players, teams)

    val canProceed = selectedGameName != null && selectedCompetitors.size >= 2

    // Create the game, its participants, and the seating decided on the second
    // step, then hand the new id back so the caller can open its scoreboard.
    fun startGame(draft: GameDraft, seating: List<PlayerEntity>) {
        if (isStarting) return
        isStarting = true
        scope.launch {
            // Best-effort location capture before persisting.
            val location = container.locationCapture.capture()
            val now = Instant.now()
            val gameNameDao = container.database.gameNameDao()
            val gameDao = container.database.gameDao()

            // Remember this as the most recently used name so it pre-selects next time.
            draft.gameNameId?.let { nameId ->
                gameNameDao.getAll().firstOrNull { it.id == nameId }?.let { name ->
                    gameNameDao.update(name.copy(lastUsedAt = now))
                }
            }

            val gameId = gameDao.insertGame(
                GameEntity(
                    title = draft.title,
                    hasTarget = draft.hasTarget,
                    targetPoints = draft.targetPoints,
                    createdAt = now,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    locationName = location?.placeName,
                    currentDealerIndex = 0,   // position 0 is the first dealer
                    currentHand = 1,
                )
            )

            draft.competitors.forEachIndexed { index, competitor ->
                gameDao.insertParticipant(GameRegistration.participant(gameId, competitor, index))
            }

            seating.forEachIndexed { position, player ->
                gameDao.insertSeat(
                    SeatEntity(gameId = gameId, playerId = player.id, position = position)
                )
            }

            onStarted(gameId)
        }
    }

    val currentDraft = draft
    if (currentDraft != null) {
        // Step 2: seating. The system back button returns to the form, like
        // popping the pushed iOS SeatingArrangementView.
        BackHandler(enabled = !isStarting) { draft = null }
        AppBackground {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = { Text("Seating") },
                        navigationIcon = {
                            IconButton(onClick = { draft = null }, enabled = !isStarting) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                },
            ) { padding ->
                Column(modifier = Modifier.padding(padding)) {
                    SeatingArrangement(
                        people = currentDraft.people,
                        direction = dealingDirection,
                        confirmTitle = "Start Game",
                        isSaving = isStarting,
                        onConfirm = { seating -> startGame(currentDraft, seating) },
                    )
                }
            }
        }
        return
    }

    // Step 1: the form.
    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("New Game") },
                    navigationIcon = {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                draft = GameDraft(
                                    title = selectedGameName?.name ?: "",
                                    hasTarget = hasTarget,
                                    targetPoints = if (hasTarget) targetPoints else null,
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
                        scope.launch { container.database.gameNameDao().delete(gameName) }
                    },
                    onAddNew = { isAddingGameName = true },
                )

                // Target score
                item {
                    CardTile(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Play to a target score", modifier = Modifier.weight(1f))
                            Switch(checked = hasTarget, onCheckedChange = { hasTarget = it })
                        }
                        if (hasTarget) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Target", modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = { targetPoints = (targetPoints - 1).coerceAtLeast(1) },
                                    enabled = targetPoints > 1,
                                ) {
                                    Icon(Icons.Filled.Remove, contentDescription = "Decrease target")
                                }
                                Text(
                                    "$targetPoints",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                IconButton(
                                    onClick = { targetPoints = (targetPoints + 1).coerceAtMost(1000) },
                                    enabled = targetPoints < 1000,
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "Increase target")
                                }
                            }
                        }
                    }
                }
                item {
                    FooterText(
                        if (hasTarget) "The first to reach $targetPoints points wins (e.g. Scopa)."
                        else "Open-ended: just track running totals (e.g. Briscola)."
                    )
                }

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

                // Location status footnote
                item {
                    val (icon, text) = when (locationStatus) {
                        LocationStatus.Granted ->
                            Icons.Filled.Place to
                                "Location will be tagged when the game starts."
                        LocationStatus.Denied ->
                            Icons.Filled.LocationOff to
                                "Location access is off, so this game won't be geo-tagged. " +
                                "Enable it in Settings."
                        LocationStatus.NotDetermined ->
                            Icons.Filled.LocationSearching to
                                "Location permission will be requested."
                    }
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
