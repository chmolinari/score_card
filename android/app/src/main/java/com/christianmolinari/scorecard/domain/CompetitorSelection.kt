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

// The roster rules that guard destructive edits: how big a team has to be, what
// deleting a player would do to the teams they belong to, and which teams are
// too small to take into a game. Port of the iOS Models/RosterCheck.swift.
//
// Note this is an *editing* rule, not a storage invariant: a smaller team can
// still arrive from an older backup or from the other platform, and restore must
// keep accepting it. Such a team is flagged here, never rejected.
object RosterCheck {

    // How many members a team needs before it can be saved or played.
    const val MINIMUM_TEAM_SIZE = 2

    // What deleting a player would leave behind in one of their teams.
    data class TeamImpact(val teamName: String, val remainingMembers: Int) {
        // True when the team would be left too small to be picked for a game.
        val fallsBelowMinimum: Boolean get() = remainingMembers < MINIMUM_TEAM_SIZE
    }

    // A team too small to compete. Teams like this can't be created any more,
    // but they still exist in older data and in backups.
    fun isUnderStrength(team: TeamWithMembers): Boolean =
        team.sortedMembers.size < MINIMUM_TEAM_SIZE

    // The teams the player belongs to and what each would be left with, in the
    // order they are shown to the user.
    fun impactOfDeleting(player: PlayerEntity, teams: List<TeamWithMembers>): List<TeamImpact> =
        teams.filter { team -> team.sortedMembers.any { it.id == player.id } }
            .map { team ->
                TeamImpact(
                    teamName = team.team.name,
                    remainingMembers = team.sortedMembers.count { it.id != player.id },
                )
            }

    // Names of the chosen team competitors that are too small to play. Player
    // competitors are never under strength.
    fun underStrengthNames(competitors: List<GameCompetitor>): List<String> =
        competitors.filterIsInstance<GameCompetitor.TeamCompetitor>()
            .filter { isUnderStrength(it.team) }
            .map { it.team.team.name }

    // Confirmation copy, built from plain values rather than entities so the
    // wording is covered by JVM unit tests without standing up a database.

    fun playerDeletionMessage(playerName: String, impacts: List<TeamImpact>): String {
        if (impacts.isEmpty()) {
            return "$playerName will be removed from this device. Past game results are not affected."
        }
        var message = "$playerName will be removed from ${sentenceList(impacts.map { it.teamName })}."
        val broken = impacts.filter { it.fallsBelowMinimum }.map { it.teamName }
        if (broken.isNotEmpty()) {
            val subject = if (broken.size == 1) "it can" else "they can"
            message += " ${sentenceList(broken)} would be left with too few members, " +
                "so $subject't be picked for a game until you add someone."
        }
        return "$message Past game results are not affected."
    }

    fun teamDeletionMessage(teamName: String, memberCount: Int): String {
        val people = if (memberCount == 1) "Its 1 member stays" else "Its $memberCount members stay"
        return "$teamName will be removed from this device. $people on the Players tab. " +
            "Past game results are not affected."
    }

    // "A", "A and B", "A, B and C" — prose form, unlike TeamWithMembers'
    // roster summary, which uses an ampersand for a compact row subtitle.
    fun sentenceList(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> "${names.dropLast(1).joinToString(", ")} and ${names.last()}"
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
