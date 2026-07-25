package com.christianmolinari.scorecard.domain

// App-wide policy for whether a competitor's total may drop below zero. Stored
// as an app-wide preference (DataStore), sharing the key string
// "allowNegativeScores" with the iOS app. Default (preference absent / false)
// clamps at zero, which suits the games the app is built around (Scopa,
// Briscola and friends are positive-only); the Settings toggle opts into
// below-zero arithmetic for games that genuinely go negative — Spades,
// Pinochle, Skat, Oh Hell.
//
// Every path that writes a score consults this, including transcribing a past
// game. Port of the iOS NegativeScores helper; see docs/scoring-rules.md.
object NegativeScores {

    // Shared DataStore key for the app-wide preference. false => clamp at zero.
    const val STORAGE_KEY = "allowNegativeScores"

    // The lowest total a competitor can hold while clamping.
    const val FLOOR = 0

    // The score delta to actually record for a requested `points` change, given
    // the competitor's `currentTotal` and whether below-zero totals are allowed.
    //
    // Additions always pass through unchanged. When clamping (below-zero not
    // allowed) a subtraction is reduced so the total stops exactly at zero, and
    // is dropped entirely (returns 0) once the total is already at or below zero
    // — it never flips a subtraction into an addition.
    fun effectiveDelta(points: Int, currentTotal: Int, allowNegative: Boolean): Int {
        if (allowNegative || points >= 0) return points
        return maxOf(points, -maxOf(currentTotal, FLOOR))
    }

    // A total the user supplied outright — correcting a finished game, or
    // transcribing a past one — brought up to the floor when clamping.
    //
    // A supplied total obeys the same preference as a subtraction does: a
    // transcribed result is still a score, so it must not be a back door past
    // the user's choice in either direction.
    fun clamped(requested: Int, allowNegative: Boolean): Int =
        if (allowNegative) requested else maxOf(requested, FLOOR)
}
