//
//  GameEdit.swift
//  ScoreCard
//
//  One correction made to a closed game's scores. The user must type a reason
//  before an edit can start, and that reason is kept here rather than discarded:
//  a finished game's result is a record, so any change to it has to stay
//  accountable. Entries are only ever added, never rewritten — see GameEdit's
//  use in the game detail screen's "Edit History".
//

import Foundation
import SwiftData

@Model
final class GameEdit {
    // CloudKit requires every stored property to be optional or have a default.
    var reason: String = ""
    var editedAt: Date = Date.now
    var game: Game?

    init(reason: String, editedAt: Date = .now) {
        self.reason = reason
        self.editedAt = editedAt
    }
}

extension Game {
    /// Commits a set of proposed final totals to this game and records the edit.
    ///
    /// The single place the edit rules live, so the editor and the tests exercise
    /// the same code rather than two copies that can drift apart:
    ///
    /// - Nothing at all is written unless a total actually moved — a game counts
    ///   as edited only when its final score differs.
    /// - Each changed competitor gets one appended `ScoreEntry` carrying the
    ///   delta. Totals are derived by summing entries, so a correction adds to
    ///   the log rather than rewriting it.
    /// - `closedAt` is deliberately left alone: an edit never reopens a game.
    ///
    /// Current totals are re-read here rather than taken from the caller, so the
    /// proposed values always mean "the final score should be this".
    ///
    /// - Returns: `true` when the edit was recorded, `false` when the proposed
    ///   totals matched the current ones and nothing was persisted.
    @discardableResult
    func applyScoreEdit(reason: String,
                        proposedTotals: [Int],
                        for participants: [GameParticipant],
                        in context: ModelContext) -> Bool {
        // Mismatched lengths would pair a total with the wrong competitor; refuse
        // rather than write a correction to somebody else's score.
        guard proposedTotals.count == participants.count else { return false }

        let originalTotals = participants.map(\.totalScore)
        guard GameScoreEdit.isChanged(before: originalTotals, after: proposedTotals) else { return false }

        for (index, participant) in participants.enumerated() {
            let delta = GameScoreEdit.delta(from: originalTotals[index], to: proposedTotals[index])
            guard delta != 0 else { continue }
            let entry = ScoreEntry(points: delta)
            entry.participant = participant
            context.insert(entry)
        }

        let edit = GameEdit(reason: reason)
        edit.game = self
        context.insert(edit)
        return true
    }
}
