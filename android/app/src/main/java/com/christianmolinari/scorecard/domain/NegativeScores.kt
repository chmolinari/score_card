package com.christianmolinari.scorecard.domain

// App-wide policy for whether a participant's running total may drop below zero.
// Stored as an app-wide preference (DataStore), sharing the key string
// "allowNegativeScores" with the iOS app. Default (preference absent / false)
// clamps any subtraction so a total stops at zero; the Settings toggle opts into
// below-zero arithmetic. Port of the iOS NegativeScores helper; see
// docs/scoring-rules.md for the cross-platform contract.
object NegativeScores {

    // Shared DataStore key for the app-wide preference. false => clamp at zero.
    const val STORAGE_KEY = "allowNegativeScores"

    // The score delta to actually record for a requested `points` change, given
    // the participant's `currentTotal` and whether below-zero totals are allowed.
    //
    // Additions always pass through unchanged. When clamping (below-zero not
    // allowed) a subtraction is reduced so the total stops exactly at zero, and
    // is dropped entirely (returns 0) once the total is already at or below zero
    // — it never flips a subtraction into an addition.
    fun effectiveDelta(points: Int, currentTotal: Int, allowNegative: Boolean): Int {
        if (allowNegative || points >= 0) return points
        return maxOf(points, -maxOf(currentTotal, 0))
    }
}
