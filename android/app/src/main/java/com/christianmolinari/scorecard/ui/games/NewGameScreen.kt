package com.christianmolinari.scorecard.ui.games

// Screen for starting a new game: name it, optionally set a target score, and
// choose the competitors (individual players or teams). Players and teams can
// be created inline here without leaving the screen, and are auto-selected
// once created. On start the current date/time and (best-effort) geolocation
// are stamped onto the game.

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Style
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.data.db.GameEntity
import com.christianmolinari.scorecard.data.db.GameNameEntity
import com.christianmolinari.scorecard.data.db.ParticipantEntity
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.ScoreCardDatabase
import com.christianmolinari.scorecard.data.db.SeatEntity
import com.christianmolinari.scorecard.data.db.TeamWithMembers
import com.christianmolinari.scorecard.domain.DealingDirection
import com.christianmolinari.scorecard.domain.FrequentPicker
import com.christianmolinari.scorecard.domain.GameNamePicker
import com.christianmolinari.scorecard.domain.NameComparator
import com.christianmolinari.scorecard.domain.playerUsageCount
import com.christianmolinari.scorecard.domain.rosterSummary
import com.christianmolinari.scorecard.domain.sortedMembers
import com.christianmolinari.scorecard.domain.teamUsageCount
import com.christianmolinari.scorecard.ui.components.AppBackground
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.GameNameEditDialog
import com.christianmolinari.scorecard.ui.components.PlayerEditDialog
import com.christianmolinari.scorecard.ui.components.PlayfulSectionHeader
import com.christianmolinari.scorecard.ui.components.SwipeToDeleteBox
import com.christianmolinari.scorecard.ui.components.TeamEditDialog
import com.christianmolinari.scorecard.ui.theme.ThemeColors
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// A competitor chosen for a game: either a single player or a team. The iOS
// version wraps the live model object because a SwiftData object's persistent
// ID changes on first save; Room ids are permanent as soon as the row is
// inserted, so here identity safely compares by row id.
private sealed interface GameCompetitor {
    val name: String

    data class PlayerCompetitor(val player: PlayerEntity) : GameCompetitor {
        override val name: String get() = player.name
    }

    data class TeamCompetitor(val team: TeamWithMembers) : GameCompetitor {
        override val name: String get() = team.team.name
    }
}

// Same competitor, regardless of staleness of the wrapped snapshot.
private fun GameCompetitor.matches(other: GameCompetitor): Boolean = when {
    this is GameCompetitor.PlayerCompetitor && other is GameCompetitor.PlayerCompetitor ->
        player.id == other.player.id
    this is GameCompetitor.TeamCompetitor && other is GameCompetitor.TeamCompetitor ->
        team.team.id == other.team.team.id
    else -> false
}

// In-flight description of the game being created, carried from the form to
// the seating step before anything is persisted (port of the iOS GameDraft).
private data class GameDraft(
    val title: String,
    val hasTarget: Boolean,
    val targetPoints: Int?,
    val competitors: List<GameCompetitor>,
    // Kept so the chosen GameName's lastUsedAt can be stamped at start.
    val gameNameId: Long?,
) {
    // All distinct individual people involved, in competitor order, with teams
    // expanded to their members. These are the people who can deal.
    val people: List<PlayerEntity>
        get() {
            val seen = mutableSetOf<Long>()
            val result = mutableListOf<PlayerEntity>()
            fun add(player: PlayerEntity) {
                if (seen.add(player.id)) result.add(player)
            }
            for (competitor in competitors) {
                when (competitor) {
                    is GameCompetitor.PlayerCompetitor -> add(competitor.player)
                    is GameCompetitor.TeamCompetitor -> competitor.team.sortedMembers.forEach(::add)
                }
            }
            return result
        }
}

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
        if (!container.prefs.hasSeededGameNames.first()) {
            seedGameNamesFromExistingGames(container.database)
            container.prefs.setHasSeededGameNames(true)
        }
        if (selectedGameNameId == null) {
            val all = container.database.gameNameDao().getAll()
            selectedGameNameId = GameNamePicker
                .defaultSelection(all, lastUsed = { it.lastUsedAt }, name = { it.name })
                ?.id
        }
    }

    val selectedGameName = gameNames.firstOrNull { it.id == selectedGameNameId }

    // Re-resolve the selection against the freshest flow emissions so names and
    // rosters stay live (iOS gets this for free by holding the model object).
    val resolvedCompetitors = selectedCompetitors.map { competitor ->
        when (competitor) {
            is GameCompetitor.PlayerCompetitor ->
                players.firstOrNull { it.id == competitor.player.id }
                    ?.let { GameCompetitor.PlayerCompetitor(it) } ?: competitor
            is GameCompetitor.TeamCompetitor ->
                teams.firstOrNull { it.team.id == competitor.team.team.id }
                    ?.let { GameCompetitor.TeamCompetitor(it) } ?: competitor
        }
    }

    // True once any team is selected. A game is between teams OR between
    // individual players — never a mix — so selecting a team turns this into a
    // team game and the players sections are hidden.
    val isTeamGame = selectedCompetitors.any { it is GameCompetitor.TeamCompetitor }

    fun isSelected(competitor: GameCompetitor): Boolean =
        selectedCompetitors.any { it.matches(competitor) }

    fun toggle(competitor: GameCompetitor) {
        val existing = selectedCompetitors.firstOrNull { it.matches(competitor) }
        if (existing != null) {
            selectedCompetitors = selectedCompetitors - existing
        } else {
            // Selecting a team makes this a team game: drop any individual
            // players already chosen so the two never mix.
            var next = selectedCompetitors
            if (competitor is GameCompetitor.TeamCompetitor) {
                next = next.filterNot { it is GameCompetitor.PlayerCompetitor }
            }
            selectedCompetitors = next + competitor
        }
    }

    // Add a competitor if it isn't already chosen (used for inline creation).
    fun select(competitor: GameCompetitor) {
        if (isSelected(competitor)) return
        // Creating a team inline turns this into a team game; clear any players.
        var next = selectedCompetitors
        if (competitor is GameCompetitor.TeamCompetitor) {
            next = next.filterNot { it is GameCompetitor.PlayerCompetitor }
        }
        selectedCompetitors = next + competitor
    }

    // Top players/teams by number of games played; shown above the full list.
    val frequentPlayers = FrequentPicker.top(
        players,
        usage = { playerUsageCount(it.id, games) },
        name = { it.name },
    )
    val frequentPlayerIds = frequentPlayers.map { it.id }.toSet()
    // Players not in the "most used" set, kept in alphabetical order.
    val otherPlayers = players.filter { it.id !in frequentPlayerIds }
    // Only split into Most Used + All when it actually helps (there are extras).
    val showsMostUsedPlayers = frequentPlayers.isNotEmpty() && otherPlayers.isNotEmpty()

    val frequentTeams = FrequentPicker.top(
        teams,
        usage = { teamUsageCount(it.team.id, games) },
        name = { it.team.name },
    )
    val frequentTeamIds = frequentTeams.map { it.team.id }.toSet()
    val otherTeams = teams.filter { it.team.id !in frequentTeamIds }
    val showsMostUsedTeams = frequentTeams.isNotEmpty() && otherTeams.isNotEmpty()

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
                val participant = when (competitor) {
                    is GameCompetitor.PlayerCompetitor -> ParticipantEntity(
                        gameId = gameId,
                        playerId = competitor.player.id,
                        nameSnapshot = competitor.player.name,
                        sortIndex = index,
                    )
                    is GameCompetitor.TeamCompetitor -> ParticipantEntity(
                        gameId = gameId,
                        teamId = competitor.team.team.id,
                        nameSnapshot = competitor.team.team.name,
                        sortIndex = index,
                    )
                }
                gameDao.insertParticipant(participant)
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
                // Game name
                item { PlayfulSectionHeader("Game", Icons.Filled.Style) }
                if (gameNames.isEmpty()) {
                    item { FooterText("No game names yet. Add one to get started.") }
                } else {
                    items(gameNames, key = { "name-${it.id}" }) { gameName ->
                        SwipeToDeleteBox(onDelete = {
                            if (selectedGameNameId == gameName.id) selectedGameNameId = null
                            scope.launch { container.database.gameNameDao().delete(gameName) }
                        }) {
                            SelectableRow(
                                name = gameName.name,
                                subtitle = null,
                                icon = Icons.Filled.Style,
                                selected = selectedGameNameId == gameName.id,
                                onClick = { selectedGameNameId = gameName.id },
                            )
                        }
                    }
                }
                item { NewItemRow("New Game Name") { isAddingGameName = true } }
                item {
                    FooterText(
                        "Pick the game you're playing, or add a new one. " +
                            "The last name you used is selected by default."
                    )
                }

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

                // Players — hidden entirely once this is a team game, because
                // individual players don't apply.
                if (!isTeamGame) {
                    if (showsMostUsedPlayers) {
                        item { PlayfulSectionHeader("Most Used Players", Icons.Filled.Person) }
                        items(frequentPlayers, key = { "freq-player-${it.id}" }) { player ->
                            val competitor = GameCompetitor.PlayerCompetitor(player)
                            SelectableRow(
                                name = player.name,
                                subtitle = null,
                                icon = Icons.Filled.Person,
                                selected = isSelected(competitor),
                                onClick = { toggle(competitor) },
                            )
                        }
                        item { PlayfulSectionHeader("All Players", Icons.Filled.Person) }
                        items(otherPlayers, key = { "player-${it.id}" }) { player ->
                            val competitor = GameCompetitor.PlayerCompetitor(player)
                            SelectableRow(
                                name = player.name,
                                subtitle = null,
                                icon = Icons.Filled.Person,
                                selected = isSelected(competitor),
                                onClick = { toggle(competitor) },
                            )
                        }
                    } else {
                        item { PlayfulSectionHeader("Players", Icons.Filled.Person) }
                        items(players, key = { "player-${it.id}" }) { player ->
                            val competitor = GameCompetitor.PlayerCompetitor(player)
                            SelectableRow(
                                name = player.name,
                                subtitle = null,
                                icon = Icons.Filled.Person,
                                selected = isSelected(competitor),
                                onClick = { toggle(competitor) },
                            )
                        }
                    }
                    item { NewItemRow("New Player") { isAddingPlayer = true } }
                }

                // Teams
                if (showsMostUsedTeams) {
                    item { PlayfulSectionHeader("Most Used Teams", Icons.Filled.Groups) }
                    items(frequentTeams, key = { "freq-team-${it.team.id}" }) { team ->
                        val competitor = GameCompetitor.TeamCompetitor(team)
                        SelectableRow(
                            name = team.team.name,
                            subtitle = team.rosterSummary,
                            icon = Icons.Filled.Groups,
                            selected = isSelected(competitor),
                            onClick = { toggle(competitor) },
                        )
                    }
                    item { PlayfulSectionHeader("All Teams", Icons.Filled.Groups) }
                    items(otherTeams, key = { "team-${it.team.id}" }) { team ->
                        val competitor = GameCompetitor.TeamCompetitor(team)
                        SelectableRow(
                            name = team.team.name,
                            subtitle = team.rosterSummary,
                            icon = Icons.Filled.Groups,
                            selected = isSelected(competitor),
                            onClick = { toggle(competitor) },
                        )
                    }
                } else {
                    item { PlayfulSectionHeader("Teams", Icons.Filled.Groups) }
                    items(teams, key = { "team-${it.team.id}" }) { team ->
                        val competitor = GameCompetitor.TeamCompetitor(team)
                        SelectableRow(
                            name = team.team.name,
                            subtitle = team.rosterSummary,
                            icon = Icons.Filled.Groups,
                            selected = isSelected(competitor),
                            onClick = { toggle(competitor) },
                        )
                    }
                }
                item { NewItemRow("New Team") { isAddingTeam = true } }

                // Playing summary, in selection order — that order becomes the
                // participants' sortIndex.
                if (resolvedCompetitors.isNotEmpty()) {
                    item { PlayfulSectionHeader("Playing", Icons.Filled.Numbers) }
                    items(resolvedCompetitors.size, key = { "playing-$it" }) { index ->
                        CardTile(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Numbers,
                                    contentDescription = null,
                                    tint = ThemeColors.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text("${index + 1}. ${resolvedCompetitors[index].name}")
                            }
                        }
                    }
                }

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
            onCreated = { newPlayer -> select(GameCompetitor.PlayerCompetitor(newPlayer)) },
        )
    }
    if (isAddingTeam) {
        TeamEditDialog(
            container = container,
            existing = null,
            onDismiss = { isAddingTeam = false },
            onCreated = { newTeam -> select(GameCompetitor.TeamCompetitor(newTeam)) },
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

// One-time backfill so users upgrading with existing games immediately see a
// useful list: create a GameName for each distinct game title (matched
// case-insensitively), stamping lastUsedAt from the most recent game that
// used it. No-op when names already exist or there are no games. The caller
// guards repeat runs with a flag; the in-method check is a second safety net.
private suspend fun seedGameNamesFromExistingGames(database: ScoreCardDatabase) {
    if (database.gameNameDao().getAll().isNotEmpty()) return

    val games = database.gameDao().getAllWithDetails()
    if (games.isEmpty()) return

    // Per case-insensitive title, keep the spelling and creation date of the
    // most recently created game that used it (so the latest spelling wins and
    // lastUsedAt reflects the newest game). Independent of fetch order.
    val byKey = mutableMapOf<String, Pair<String, Instant>>()
    for (game in games) {
        val title = game.game.title.trim()
        if (title.isEmpty()) continue
        val key = title.lowercase()
        val current = byKey[key]
        if (current != null && !current.second.isBefore(game.game.createdAt)) continue
        byKey[key] = title to game.game.createdAt
    }

    val now = Instant.now()
    for ((name, lastUsed) in byKey.values) {
        database.gameNameDao().insert(
            GameNameEntity(name = name, createdAt = now, lastUsedAt = lastUsed)
        )
    }
}

// A tappable row with a check mark when selected, used for game names,
// players, and teams alike.
@Composable
private fun SelectableRow(
    name: String,
    subtitle: String?,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    CardTile(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = ThemeColors.accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = ThemeColors.accent,
                )
            }
        }
    }
}

// Inline "New …" creation row at the end of a selector section.
@Composable
private fun NewItemRow(label: String, onClick: () -> Unit) {
    CardTile(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = ThemeColors.accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(label, color = ThemeColors.accent)
        }
    }
}

// Small secondary footnote under a section, like an iOS Form footer.
@Composable
private fun FooterText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}
