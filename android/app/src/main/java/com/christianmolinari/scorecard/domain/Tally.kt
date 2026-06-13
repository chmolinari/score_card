package com.christianmolinari.scorecard.domain

import kotlin.math.roundToInt

// A small value type summarising a player's or team's record: how many
// finished games they played, how many they won, and how many are in progress.
// Computed on the fly from their game participations — nothing extra is stored.
data class Tally(
    // Number of finished (closed) games this competitor took part in.
    val played: Int = 0,
    // Finished games won outright (sole top score). Draws are counted separately.
    val won: Int = 0,
    // Finished games that ended in a draw (a tie for the top score).
    val drawn: Int = 0,
    // Games currently still open.
    val inProgress: Int = 0,
) {
    // Whole-number win percentage over finished games, or null if none played.
    // roundToInt rounds half-up, matching Swift's .rounded() for non-negative input.
    val winPercentage: Int?
        get() = if (played > 0) (won.toDouble() / played.toDouble() * 100).roundToInt() else null

    // True when there is nothing to show yet.
    val isEmpty: Boolean
        get() = played == 0 && inProgress == 0
}
