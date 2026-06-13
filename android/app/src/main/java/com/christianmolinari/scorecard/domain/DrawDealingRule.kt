package com.christianmolinari.scorecard.domain

// Who deals the next hand when a hand ends in a draw (nobody scored that hand).
// Stored as an app-wide preference (DataStore); the key and raw values are
// shared with the iOS app. When set to Ask, the scoreboard asks each time a
// hand is drawn instead of deciding automatically.
enum class DrawDealingRule(
    // Raw values are stable preference strings shared with the iOS app —
    // renaming them would silently reset everyone's saved preference, so don't.
    val rawValue: String,
    // Title-case label for pickers.
    val label: String,
) {
    // The last dealer deals the next hand again (the draw doesn't move the deal).
    Redeal("redeal", "Same dealer deals again"),
    // The deal passes to the next dealer, as it does after a scored hand.
    PassOn("passOn", "Pass to the next dealer"),
    // Ask the user, each time a hand is drawn, who should deal next.
    Ask("ask", "Ask each time");

    companion object {
        // Ask is the default, matching the iOS app.
        fun fromRaw(raw: String?): DrawDealingRule =
            entries.firstOrNull { it.rawValue == raw } ?: Ask
    }
}
