//
//  GameParticipant.swift
//  ScoreCard
//
//  One competitor inside one game. A competitor is EITHER a single player OR a
//  team — exactly one of `player`/`team` is set. A name snapshot is stored so
//  game history survives even if the underlying player/team is later deleted.
//

import Foundation
import SwiftData

@Model
final class GameParticipant {
    // Snapshot of the competitor's name at the time the game was created. Used as
    // a fallback for history once the linked player/team is gone.
    var nameSnapshot: String = ""

    // Preserves the order participants were added in (for stable, non-score
    // tie-breaking in the scoreboard).
    var sortIndex: Int = 0

    var game: Game?
    var player: Player?
    var team: Team?

    // The running score is derived from individual entries so that undo is exact
    // and the full scoring history is auditable.
    @Relationship(deleteRule: .cascade, inverse: \ScoreEntry.participant)
    var scoreEntries: [ScoreEntry]? = []

    init(player: Player, sortIndex: Int = 0) {
        self.player = player
        self.nameSnapshot = player.name
        self.sortIndex = sortIndex
    }

    init(team: Team, sortIndex: Int = 0) {
        self.team = team
        self.nameSnapshot = team.name
        self.sortIndex = sortIndex
    }

    /// Restore-only initializer for a competitor whose underlying player/team is
    /// no longer present (only the name snapshot survives). Used when importing
    /// a backup; not used during normal play.
    init(nameSnapshot: String, sortIndex: Int = 0) {
        self.nameSnapshot = nameSnapshot
        self.sortIndex = sortIndex
    }

    /// Live display name: prefer the linked entity's current name, else the snapshot.
    var displayName: String {
        if let player { return player.name }
        if let team { return team.name }
        return nameSnapshot
    }

    /// True when this competitor is a team rather than a single player.
    var isTeam: Bool { team != nil }

    /// Subtitle describing the competitor (team roster, or "Player").
    var subtitle: String {
        if let team { return team.rosterSummary }
        return "Player"
    }

    /// Current total, summed from every score entry (may be negative).
    var totalScore: Int {
        (scoreEntries ?? []).reduce(0) { $0 + $1.points }
    }

    /// Whether this competitor was the sole winner of its (closed) game. A tie
    /// for the top score is NOT a win — it's a draw (see `isDraw`). Always false
    /// while the game is still open.
    var isWinner: Bool {
        guard let game, !game.isOpen else { return false }
        let top = game.topScorers
        return top.count == 1 && top.first === self
    }

    /// Whether this competitor tied for the top score in a closed (drawn) game.
    /// Only the competitors sharing the winning score drew; lower scorers did not.
    var isDraw: Bool {
        guard let game, game.isDraw else { return false }
        return game.topScorers.contains { $0 === self }
    }

    /// Score entries newest-first, for the per-competitor history view.
    var sortedEntries: [ScoreEntry] {
        (scoreEntries ?? []).sorted { $0.timestamp > $1.timestamp }
    }
}
