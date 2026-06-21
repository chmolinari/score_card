package com.christianmolinari.scorecard.ui.components

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Small formatting helpers shared across the game screens.
object GameFormatting {
    // "1 Jun 2026 at 14:30" style stamp for a game's date + time, in the
    // user's locale and time zone.
    fun dateTime(instant: Instant): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
            .format(instant)

    // Compact duration like "1h 12m" between two instants.
    fun duration(from: Instant, to: Instant): String {
        val seconds = Duration.between(from, to).seconds.coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "less than a minute"
        }
    }

    // "+3" for positive deltas; zero and negatives already read correctly.
    fun signedPoints(value: Int): String = if (value > 0) "+$value" else value.toString()
}
