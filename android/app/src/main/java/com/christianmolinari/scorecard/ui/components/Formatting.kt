package com.christianmolinari.scorecard.ui.components

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Small formatting helpers shared across the game screens.
object GameFormatting {
    // "1 Jun 2026 at 14:30" style stamp for a game's date + time, in the
    // user's locale and time zone. A stamp at exactly local midnight means
    // "date known, time unknown" (a registered past game whose time of day
    // wasn't set), so the time is omitted — live games are stamped with
    // millisecond precision and essentially never land on 00:00:00.000. The
    // zone parameter exists for tests; production callers use the default.
    // `dateOnly` says whether the stamp's time of day is meaningful. Passing null
    // (the default) falls back to inferring it from the stamp sitting at the
    // start of its local day — correct only for games recorded before
    // GameEntity.playedDateOnly existed, since that inference breaks when the
    // device changes time zone and mistakes a deliberate 00:00 for "no time".
    fun dateTime(
        instant: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        dateOnly: Boolean? = null,
    ): String {
        // Compared against the zone's actual start of day, not the literal
        // LocalTime.MIDNIGHT: a date-only stamp is built with atStartOfDay,
        // which skips forward over a daylight-saving gap, so on a transition
        // day midnight does not exist and the marker would be missed — showing
        // a time of day the user explicitly declined to give. iOS compares
        // against Calendar.startOfDay for the same reason.
        val zoned = instant.atZone(zone)
        val isDateOnly = dateOnly
            ?: (zoned.toLocalDate().atStartOfDay(zone).toInstant() == instant)
        val style =
            if (isDateOnly) {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            } else {
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            }
        return style.withZone(zone).format(instant)
    }

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

    // "1 point" / "3 points", for the accessibility names of the scoring
    // buttons, whose visible labels are bare numbers that repeat down the board.
    fun points(value: Int): String = if (value == 1) "$value point" else "$value points"
}
