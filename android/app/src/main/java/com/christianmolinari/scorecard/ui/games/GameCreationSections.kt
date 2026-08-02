// Form sections shared by the New Game and Register Past Game screens: the
// game-name selector, the player/team selectors with their Most Used / All
// split, and the "Playing" summary. Extracted from NewGameScreen when the
// register flow was added (mirroring the iOS CompetitorSelectionSections /
// GameNameSection extraction) so the two forms can never drift apart.

package com.christianmolinari.scorecard.ui.games

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.christianmolinari.scorecard.AppContainer
import com.christianmolinari.scorecard.data.db.GameNameEntity
import com.christianmolinari.scorecard.data.db.GameWithDetails
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.ScoreCardDatabase
import com.christianmolinari.scorecard.data.db.TeamWithMembers
import com.christianmolinari.scorecard.domain.FrequentPicker
import com.christianmolinari.scorecard.domain.GameCompetitor
import com.christianmolinari.scorecard.domain.GameNamePicker
import com.christianmolinari.scorecard.domain.RosterCheck
import com.christianmolinari.scorecard.domain.matches
import com.christianmolinari.scorecard.domain.playerUsageCount
import com.christianmolinari.scorecard.domain.rosterSummary
import com.christianmolinari.scorecard.domain.sortedMembers
import com.christianmolinari.scorecard.domain.teamUsageCount
import com.christianmolinari.scorecard.ui.components.CardTile
import com.christianmolinari.scorecard.ui.components.PlayfulSectionHeader
import com.christianmolinari.scorecard.ui.components.SwipeToDeleteBox
import com.christianmolinari.scorecard.ui.theme.ThemeColors
import java.time.Instant
import kotlinx.coroutines.flow.first

// In-flight description of the game being created or registered, carried from
// the form to the next step before anything is persisted (port of the iOS
// GameDraft).
data class GameDraft(
    val title: String,
    val hasTarget: Boolean,
    val targetPoints: Int?,
    val competitors: List<GameCompetitor>,
    // Kept so the chosen GameName's lastUsedAt can be stamped at save.
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

// The "Game" section: pick the game name from the reusable list, add a new
// one inline, or swipe a stale name away.
fun LazyListScope.gameNameSelectionSection(
    gameNames: List<GameNameEntity>,
    selectedGameNameId: Long?,
    onSelect: (Long) -> Unit,
    onDelete: (GameNameEntity) -> Unit,
    onAddNew: () -> Unit,
) {
    item { PlayfulSectionHeader("Game", Icons.Filled.Style) }
    if (gameNames.isEmpty()) {
        item { FooterText("No game names yet. Add one to get started.") }
    } else {
        items(gameNames, key = { "name-${it.id}" }) { gameName ->
            SwipeToDeleteBox(onDelete = { onDelete(gameName) }) {
                SelectableRow(
                    name = gameName.name,
                    subtitle = null,
                    icon = Icons.Filled.Style,
                    selected = selectedGameNameId == gameName.id,
                    onClick = { onSelect(gameName.id) },
                )
            }
        }
    }
    item { NewItemRow("New Game Name") { onAddNew() } }
    item {
        FooterText(
            "Pick the game you're playing, or add a new one. " +
                "The last name you used is selected by default."
        )
    }
}

// The player and team selectors. Players are hidden entirely once a team is
// selected, because a game is players-only or teams-only, never a mix.
fun LazyListScope.competitorSelectionSections(
    players: List<PlayerEntity>,
    teams: List<TeamWithMembers>,
    games: List<GameWithDetails>,
    selectedCompetitors: List<GameCompetitor>,
    onToggle: (GameCompetitor) -> Unit,
    onAddPlayer: () -> Unit,
    onAddTeam: () -> Unit,
) {
    val isTeamGame = selectedCompetitors.any { it is GameCompetitor.TeamCompetitor }
    fun isSelected(competitor: GameCompetitor): Boolean =
        selectedCompetitors.any { it.matches(competitor) }

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

    fun LazyListScope.playerRows(source: List<PlayerEntity>, keyPrefix: String) {
        items(source, key = { "$keyPrefix-${it.id}" }) { player ->
            val competitor = GameCompetitor.PlayerCompetitor(player)
            SelectableRow(
                name = player.name,
                subtitle = null,
                icon = Icons.Filled.Person,
                selected = isSelected(competitor),
                onClick = { onToggle(competitor) },
            )
        }
    }

    fun LazyListScope.teamRows(source: List<TeamWithMembers>, keyPrefix: String) {
        items(source, key = { "$keyPrefix-${it.team.id}" }) { team ->
            val competitor = GameCompetitor.TeamCompetitor(team)
            // A team left below the minimum (by a deletion, or arriving from an
            // older backup) can't be created any more but can still exist, so it
            // is shown with the reason and the fix rather than silently omitted.
            val underStrength = RosterCheck.isUnderStrength(team)
            SelectableRow(
                name = team.team.name,
                subtitle = team.rosterSummary,
                icon = Icons.Filled.Groups,
                selected = isSelected(competitor),
                onClick = { onToggle(competitor) },
                enabled = !underStrength,
                warning = if (underStrength) {
                    val has = if (team.sortedMembers.size == 1) "Only 1 member" else "No members"
                    "$has — add another on the Teams tab"
                } else {
                    null
                },
            )
        }
    }

    if (!isTeamGame) {
        if (showsMostUsedPlayers) {
            item { PlayfulSectionHeader("Most Used Players", Icons.Filled.Person) }
            playerRows(frequentPlayers, keyPrefix = "freq-player")
            item { PlayfulSectionHeader("All Players", Icons.Filled.Person) }
            playerRows(otherPlayers, keyPrefix = "player")
        } else {
            item { PlayfulSectionHeader("Players", Icons.Filled.Person) }
            playerRows(players, keyPrefix = "player")
        }
        item { NewItemRow("New Player") { onAddPlayer() } }
    }

    if (showsMostUsedTeams) {
        item { PlayfulSectionHeader("Most Used Teams", Icons.Filled.Groups) }
        teamRows(frequentTeams, keyPrefix = "freq-team")
        item { PlayfulSectionHeader("All Teams", Icons.Filled.Groups) }
        teamRows(otherTeams, keyPrefix = "team")
    } else {
        item { PlayfulSectionHeader("Teams", Icons.Filled.Groups) }
        teamRows(teams, keyPrefix = "team")
    }
    item { NewItemRow("New Team") { onAddTeam() } }
}

// The "Playing" summary, in selection order — that order becomes the
// participants' sortIndex.
fun LazyListScope.playingSummarySection(competitors: List<GameCompetitor>) {
    if (competitors.isEmpty()) return
    item { PlayfulSectionHeader("Playing", Icons.Filled.Numbers) }
    items(competitors.size, key = { "playing-$it" }) { index ->
        CardTile(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Numbers,
                    contentDescription = null,
                    tint = ThemeColors.accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text("${index + 1}. ${competitors[index].name}")
            }
        }
    }
}

// One-time seeding of the name list from existing games, then the default
// pre-selection (the most recently used name). Runs from a LaunchedEffect
// when either form opens, so the default reflects the latest "last used".
suspend fun prepareGameNameSelection(container: AppContainer): Long? {
    if (!container.prefs.hasSeededGameNames.first()) {
        seedGameNamesFromExistingGames(container.database)
        container.prefs.setHasSeededGameNames(true)
    }
    val all = container.database.gameNameDao().getAll()
    return GameNamePicker
        .defaultSelection(all, lastUsed = { it.lastUsedAt }, name = { it.name })
        ?.id
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
fun SelectableRow(
    name: String,
    subtitle: String?,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    // A row that exists but can't be chosen, with the reason why. Defaults keep
    // every other caller (game names, players) exactly as it was.
    enabled: Boolean = true,
    warning: String? = null,
) {
    CardTile(modifier = Modifier.fillMaxWidth(), onClick = { if (enabled) onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (warning != null) Icons.Filled.Warning else icon,
                contentDescription = null,
                tint = if (warning != null) MaterialTheme.colorScheme.error else ThemeColors.accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (warning != null) {
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
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
fun NewItemRow(label: String, onClick: () -> Unit) {
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
fun FooterText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}
