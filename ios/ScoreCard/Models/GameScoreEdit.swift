//
//  GameScoreEdit.swift
//  ScoreCard
//
//  The arithmetic behind editing a closed game's scores: what total can actually
//  be stored, what score entry moves a competitor to it, and whether anything
//  changed at all. Kept free of SwiftData so it is trivially unit-testable and so
//  the rules don't end up buried in a view.
//

import Foundation

enum GameScoreEdit {
    /// The total actually storable for a requested new total.
    ///
    /// The app-wide below-zero preference (see `NegativeScores` and
    /// docs/scoring-rules.md) applies to corrections exactly as it does to live
    /// scoring: with it off, a total can't be driven below zero, so anything
    /// negative lands on zero instead.
    static func normalizedTotal(_ requested: Int, allowNegative: Bool) -> Int {
        NegativeScores.clamped(requested, allowNegative: allowNegative)
    }

    /// The score entry delta needed to move a competitor from one total to another.
    ///
    /// A total is derived by summing the competitor's entries, so an edit is
    /// applied by appending one more entry rather than by rewriting history —
    /// this is the value that entry must carry.
    static func delta(from before: Int, to after: Int) -> Int {
        after - before
    }

    /// Whether a proposed set of totals differs from the current ones.
    ///
    /// Compared element-wise in the same order (both arrays come from the same
    /// participant ordering). A game is only recorded as edited when the final
    /// score actually moved, so this gates both the Save button and the commit.
    static func isChanged(before: [Int], after: [Int]) -> Bool {
        before != after
    }
}
