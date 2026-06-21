package com.christianmolinari.scorecard.domain

import com.christianmolinari.scorecard.data.db.GameWithDetails
import com.christianmolinari.scorecard.data.db.ParticipantWithDetails
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.ScoreEntryEntity
import com.christianmolinari.scorecard.data.db.SeatWithPlayer
import com.christianmolinari.scorecard.data.db.TeamWithMembers

// Pure domain logic over the Room relation types, porting the iOS computed
// properties on Game, GameParticipant and Team.

// MARK: Participant

// Current total, summed from every score entry (may be negative). The running
// score is derived from individual entries so that undo is exact and the full
// scoring history is auditable.
val ParticipantWithDetails.totalScore: Int
    get() = entries.sumOf { it.points }

// Live display name: prefer the linked entity's current name, else the
// snapshot taken when the game was created (which survives deletion of the
// underlying player/team).
val ParticipantWithDetails.displayName: String
    get() = player?.name ?: team?.team?.name ?: participant.nameSnapshot

// True when this competitor is a team rather than a single player.
val ParticipantWithDetails.isTeamCompetitor: Boolean
    get() = team != null

// Subtitle describing the competitor (team roster, or "Player").
val ParticipantWithDetails.subtitle: String
    get() = team?.rosterSummary ?: "Player"

// Score entries newest-first, for the per-competitor history view.
val ParticipantWithDetails.sortedEntries: List<ScoreEntryEntity>
    get() = entries.sortedByDescending { it.timestamp }

// MARK: Team

// Members sorted by name for display.
val TeamWithMembers.sortedMembers: List<PlayerEntity>
    get() = members.sortedWith(compareBy(NameComparator) { it.name })

// "Alice, Bob & Carol" style summary of the roster.
val TeamWithMembers.rosterSummary: String
    get() {
        val names = sortedMembers.map { it.name }
        return when (names.size) {
            0 -> "No members"
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
        }
    }

// MARK: Game — scores and ranking

// A game is "open" (still being scored) until it is explicitly closed.
val GameWithDetails.isOpen: Boolean
    get() = game.closedAt == null

// Participants paired with their total score, ranked highest-first.
//
// Each participant's total is summed from its score entries exactly once here,
// so callers that need both the ranking and the scores (the live scoreboard)
// don't re-walk every entry multiple times per render.
val GameWithDetails.rankedScores: List<Pair<ParticipantWithDetails, Int>>
    get() = participants
        .map { it to it.totalScore }
        .sortedWith(
            compareByDescending<Pair<ParticipantWithDetails, Int>> { it.second }
                .thenBy { it.first.participant.sortIndex }
        )

// Participants unwrapped, ranked by score (highest first) for the scoreboard.
val GameWithDetails.rankedParticipants: List<ParticipantWithDetails>
    get() = rankedScores.map { it.first }

// The participant currently in the lead, if the game has any.
val GameWithDetails.leader: ParticipantWithDetails?
    get() = rankedScores.firstOrNull()?.first

// Competitors that share the top score. While the game is open this is the
// current front-runner(s); once closed it's the final winner(s) — more than
// one means the game ended in a draw.
val GameWithDetails.topScorers: List<ParticipantWithDetails>
    get() {
        val ranked = rankedScores
        val best = ranked.firstOrNull()?.second ?: return emptyList()
        return ranked.filter { it.second == best }.map { it.first }
    }

// A closed game is a draw when no single competitor has the top score.
val GameWithDetails.isDraw: Boolean
    get() = !isOpen && topScorers.size > 1

// MARK: Game — seating and dealing

// Seats ordered counter-clockwise from the first dealer (position 0).
val GameWithDetails.orderedSeats: List<SeatWithPlayer>
    get() = seats.sortedBy { it.seat.position }

// Whether a seating order / dealer has been set for this game.
val GameWithDetails.hasSeating: Boolean
    get() = seats.isNotEmpty()

// The player dealing the current hand, if seating is set.
val GameWithDetails.currentDealer: PlayerEntity?
    get() = dealerAtOffset(0)

// The player who deals the next hand, given the dealing direction.
fun GameWithDetails.nextDealer(direction: DealingDirection): PlayerEntity? =
    dealerAtOffset(direction.step)

// The dealer index after moving the deal to the next player in the given
// direction (new hand). Pure — callers persist the result via updateGame.
fun GameWithDetails.advancedDealerIndex(direction: DealingDirection): Int {
    val count = orderedSeats.size
    if (count == 0) return game.currentDealerIndex
    return ((game.currentDealerIndex + direction.step) % count + count) % count
}

// Player seated `offset` steps from the current dealer (wrapping).
private fun GameWithDetails.dealerAtOffset(offset: Int): PlayerEntity? {
    val ordered = orderedSeats
    if (ordered.isEmpty()) return null
    val count = ordered.size
    val index = ((game.currentDealerIndex + offset) % count + count) % count
    return ordered[index].player
}

// Competitors in a fixed table order for the live scoreboard, so the rows
// don't shuffle by score during play.
//
// The first dealer (the one drawn at seat position 0) — or the team that
// player belongs to — is on top; everyone else follows the dealing rotation in
// the given direction. A team is placed by its earliest-dealing member ("the
// first of its players to deal next"). When no seating has been set up yet,
// falls back to the order the participants were added.
fun GameWithDetails.participantsInDealingOrder(direction: DealingDirection): List<ParticipantWithDetails> {
    val ordered = orderedSeats
    if (ordered.isEmpty()) {
        return participants.sortedBy { it.participant.sortIndex }
    }

    // Dealing rank of each seated player: 0 for the first dealer, then in the
    // order the deal passes. Seats are stored counter-clockwise from
    // position 0, so counter-clockwise dealing keeps that order and clockwise
    // dealing reverses everyone after the first dealer.
    val count = ordered.size
    val rankOf = mutableMapOf<Long, Int>()
    for (seat in ordered) {
        val playerId = seat.player?.id ?: continue
        rankOf[playerId] = ((seat.seat.position * direction.step) % count + count) % count
    }

    fun dealingRank(participant: ParticipantWithDetails): Int {
        participant.player?.let { return rankOf[it.id] ?: Int.MAX_VALUE }
        participant.team?.let { team ->
            return team.members.mapNotNull { rankOf[it.id] }.minOrNull() ?: Int.MAX_VALUE
        }
        return Int.MAX_VALUE
    }

    return participants.sortedWith(
        compareBy({ dealingRank(it) }, { it.participant.sortIndex })
    )
}

// MARK: Game — outcomes

// Whether the competitor was the sole winner of this (closed) game. A tie for
// the top score is NOT a win — it's a draw (see isDrawFor). Always false while
// the game is still open.
fun GameWithDetails.isSoleWinner(p: ParticipantWithDetails): Boolean {
    if (isOpen) return false
    val top = topScorers
    return top.size == 1 && top.first().participant.id == p.participant.id
}

// Whether the competitor tied for the top score in a closed (drawn) game. Only
// the competitors sharing the winning score drew; lower scorers did not.
fun GameWithDetails.isDrawFor(p: ParticipantWithDetails): Boolean {
    if (!isDraw) return false
    return topScorers.any { it.participant.id == p.participant.id }
}

// MARK: Tallies and usage

// Win/play record for a player across all games: open games count as
// in-progress; closed games count as played, plus won/drawn per the outcome.
fun playerTally(playerId: Long, games: List<GameWithDetails>): Tally =
    tally(games) { it.participant.playerId == playerId }

// Win/play record for a team across all games.
fun teamTally(teamId: Long, games: List<GameWithDetails>): Tally =
    tally(games) { it.participant.teamId == teamId }

private fun tally(games: List<GameWithDetails>, matches: (ParticipantWithDetails) -> Boolean): Tally {
    var played = 0
    var won = 0
    var drawn = 0
    var inProgress = 0
    for (game in games) {
        for (participant in game.participants) {
            if (!matches(participant)) continue
            if (game.isOpen) {
                inProgress += 1
            } else {
                played += 1
                if (game.isSoleWinner(participant)) {
                    won += 1
                } else if (game.isDrawFor(participant)) {
                    drawn += 1
                }
            }
        }
    }
    return Tally(played = played, won = won, drawn = drawn, inProgress = inProgress)
}

// How many games the player has appeared in (open or finished). Used to
// surface frequently used players in the New Game selector.
fun playerUsageCount(playerId: Long, games: List<GameWithDetails>): Int =
    games.sumOf { game -> game.participants.count { it.participant.playerId == playerId } }

// How many games the team has appeared in (open or finished).
fun teamUsageCount(teamId: Long, games: List<GameWithDetails>): Int =
    games.sumOf { game -> game.participants.count { it.participant.teamId == teamId } }
