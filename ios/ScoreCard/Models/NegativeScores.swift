//
//  NegativeScores.swift
//  ScoreCard
//
//  App-wide policy for whether a competitor's total may drop below zero. Stored
//  as an app-wide preference (UserDefaults via @AppStorage), shared by key
//  string with the Android app. Default (key absent / `false`) clamps at zero,
//  which suits the games the app is built around (Scopa, Briscola and friends
//  are positive-only); the Settings toggle opts into below-zero arithmetic for
//  games that genuinely go negative — Spades, Pinochle, Skat, Oh Hell.
//
//  Every path that writes a score consults this, including transcribing a past
//  game. See docs/scoring-rules.md.
//

import Foundation

enum NegativeScores {
    /// Shared UserDefaults key for the app-wide preference. `false` ⇒ clamp at zero.
    static let storageKey = "allowNegativeScores"

    /// The lowest total a competitor can hold while clamping.
    static let floor = 0

    /// The score delta to actually record for a requested `points` change, given
    /// the competitor's `currentTotal` and whether below-zero totals are allowed.
    ///
    /// Additions always pass through unchanged. When clamping (below-zero not
    /// allowed) a subtraction is reduced so the total stops exactly at zero, and
    /// is dropped entirely (returns 0) once the total is already at or below zero
    /// — it never flips a subtraction into an addition.
    static func effectiveDelta(points: Int, currentTotal: Int, allowNegative: Bool) -> Int {
        guard !allowNegative, points < 0 else { return points }
        return max(points, -max(currentTotal, floor))
    }

    /// A total the user supplied outright — correcting a finished game, or
    /// transcribing a past one — brought up to the floor when clamping.
    ///
    /// A supplied total obeys the same preference as a subtraction does: a
    /// transcribed result is still a score, so it must not be a back door past
    /// the user's choice in either direction.
    static func clamped(_ requested: Int, allowNegative: Bool) -> Int {
        allowNegative ? requested : max(requested, floor)
    }
}
