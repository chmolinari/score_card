package com.christianmolinari.scorecard.domain

import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.TeamWithMembers

// A competitor chosen for a game: either a single player or a team. The iOS
// version wraps the live model object because a SwiftData object's persistent
// ID changes on first save; Room ids are permanent as soon as the row is
// inserted, so here identity safely compares by row id. Shared by the New Game
// and Register Past Game forms.
sealed interface GameCompetitor {
    val name: String

    data class PlayerCompetitor(val player: PlayerEntity) : GameCompetitor {
        override val name: String get() = player.name
    }

    data class TeamCompetitor(val team: TeamWithMembers) : GameCompetitor {
        override val name: String get() = team.team.name
    }
}

// Same competitor, regardless of staleness of the wrapped snapshot.
fun GameCompetitor.matches(other: GameCompetitor): Boolean = when {
    this is GameCompetitor.PlayerCompetitor && other is GameCompetitor.PlayerCompetitor ->
        player.id == other.player.id
    this is GameCompetitor.TeamCompetitor && other is GameCompetitor.TeamCompetitor ->
        team.team.id == other.team.team.id
    else -> false
}

// Selection semantics shared by both game forms, as pure functions (port of
// the iOS CompetitorSelectionRules). A game is between teams OR between
// individual players — never a mix — so choosing a team drops any players.
object CompetitorSelectionRules {

    // Tap on a selector row: deselect if already chosen, otherwise add.
    fun toggling(competitor: GameCompetitor, selection: List<GameCompetitor>): List<GameCompetitor> {
        val existing = selection.firstOrNull { it.matches(competitor) }
        if (existing != null) return selection - existing
        return adding(competitor, selection)
    }

    // Add a competitor if it isn't already chosen (used for inline creation).
    fun adding(competitor: GameCompetitor, selection: List<GameCompetitor>): List<GameCompetitor> {
        if (selection.any { it.matches(competitor) }) return selection
        // Selecting a team makes this a team game: drop any individual
        // players already chosen so the two never mix.
        var next = selection
        if (competitor is GameCompetitor.TeamCompetitor) {
            next = next.filterNot { it is GameCompetitor.PlayerCompetitor }
        }
        return next + competitor
    }
}

// Re-resolve a selection against the freshest flow emissions so names and
// rosters stay live (iOS gets this for free by holding the model object).
fun resolveCompetitors(
    selection: List<GameCompetitor>,
    players: List<PlayerEntity>,
    teams: List<TeamWithMembers>,
): List<GameCompetitor> = selection.map { competitor ->
    when (competitor) {
        is GameCompetitor.PlayerCompetitor ->
            players.firstOrNull { it.id == competitor.player.id }
                ?.let { GameCompetitor.PlayerCompetitor(it) } ?: competitor
        is GameCompetitor.TeamCompetitor ->
            teams.firstOrNull { it.team.id == competitor.team.team.id }
                ?.let { GameCompetitor.TeamCompetitor(it) } ?: competitor
    }
}
