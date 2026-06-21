package com.christianmolinari.scorecard.domain

// The direction the deal passes around the table after each hand. Stored as an
// app-wide preference (DataStore); seats are recorded counter-clockwise, so
// this just decides whether the deal steps +1 or -1.
enum class DealingDirection(
    // Raw values are stable preference strings shared with the iOS app —
    // renaming them would silently reset everyone's saved preference, so don't.
    val rawValue: String,
    // Step applied to the current dealer's seat index when the deal advances.
    val step: Int,
    // Title-case label for pickers.
    val label: String,
    // Lowercase adverb for sentences ("the deal passes counter-clockwise").
    val adverb: String,
) {
    CounterClockwise("counterClockwise", 1, "Counter-clockwise", "counter-clockwise"),
    Clockwise("clockwise", -1, "Clockwise", "clockwise");

    companion object {
        fun fromRaw(raw: String?): DealingDirection =
            entries.firstOrNull { it.rawValue == raw } ?: CounterClockwise
    }
}
