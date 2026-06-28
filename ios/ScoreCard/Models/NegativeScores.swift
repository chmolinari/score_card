//
//  NegativeScores.swift
//  ScoreCard
//
//  App-wide policy for whether a participant's running total may drop below
//  zero. Stored as an app-wide preference (UserDefaults via @AppStorage), shared
//  by key string with the Android app. Default (key absent / `false`) clamps any
//  subtraction so a total stops at zero; the Settings toggle opts into below-zero
//  arithmetic. See docs/scoring-rules.md.
//

import Foundation

enum NegativeScores {
    /// Shared UserDefaults key for the app-wide preference. `false` ⇒ clamp at zero.
    static let storageKey = "allowNegativeScores"

    /// The score delta to actually record for a requested `points` change, given
    /// the participant's `currentTotal` and whether below-zero totals are allowed.
    ///
    /// Additions always pass through unchanged. When clamping (below-zero not
    /// allowed) a subtraction is reduced so the total stops exactly at zero, and
    /// is dropped entirely (returns 0) once the total is already at or below zero
    /// — it never flips a subtraction into an addition.
    static func effectiveDelta(points: Int, currentTotal: Int, allowNegative: Bool) -> Int {
        guard !allowNegative, points < 0 else { return points }
        return max(points, -max(currentTotal, 0))
    }
}
