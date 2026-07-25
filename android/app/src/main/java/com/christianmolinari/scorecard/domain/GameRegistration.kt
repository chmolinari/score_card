package com.christianmolinari.scorecard.domain

import com.christianmolinari.scorecard.data.db.GameEntity
import com.christianmolinari.scorecard.data.db.ParticipantEntity
import com.christianmolinari.scorecard.data.db.ScoreEntryEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// Registering a game that was played outside the app (before the app existed,
// or scored on paper): the user picks the competitors and enters each one's
// final total, and the app stores an already-closed game backdated to the
// played-on date. Port of the iOS GameRegistration. These are pure builders so
// the semantics unit-test on the JVM; the screen performs the DAO inserts.
object GameRegistration {

    // The instant a registered game is stamped with, honoring the date and
    // time opt-outs:
    // - date and time set: taken verbatim;
    // - date only: local midnight of that day — the display layer treats an
    //   exact-midnight stamp as "time unknown" and omits the time;
    // - no date: the moment of registration, so the game files under today.
    // Never in the future: the date picker already caps at today, so the
    // clamp only catches "today" combined with a not-yet-reached time.
    fun playedInstant(date: LocalDate?, time: LocalTime?, zone: ZoneId, now: Instant): Instant {
        if (date == null) return now
        val selected =
            if (time == null) date.atStartOfDay(zone)
            else date.atTime(time).atZone(zone)
        return minOf(selected.toInstant(), now)
    }

    // Trimmed location name, or null when blank.
    fun normalizedLocation(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }

    // The already-closed, backdated game row. No target (a transcription only
    // has final totals, so the game reads as open-ended) and no coordinates
    // (the phone's current position says nothing about where a past game was
    // played). createdAt == closedAt is also what hides the meaningless
    // "Ended … · less than a minute" line in the game info section.
    fun game(title: String, playedAt: Instant, locationName: String?): GameEntity =
        GameEntity(
            title = title,
            hasTarget = false,
            targetPoints = null,
            createdAt = playedAt,
            closedAt = playedAt,
            locationName = normalizedLocation(locationName),
        )

    // One participant per competitor, in selection order (that order becomes
    // the sortIndex, like the New Game flow).
    fun participant(gameId: Long, competitor: GameCompetitor, index: Int): ParticipantEntity =
        when (competitor) {
            is GameCompetitor.PlayerCompetitor -> ParticipantEntity(
                gameId = gameId,
                playerId = competitor.player.id,
                nameSnapshot = competitor.player.name,
                sortIndex = index,
            )
            is GameCompetitor.TeamCompetitor -> ParticipantEntity(
                gameId = gameId,
                teamId = competitor.team.team.id,
                nameSnapshot = competitor.team.team.name,
                sortIndex = index,
            )
        }

    // A competitor's final total as its single score entry, stamped with the
    // played-on instant like the game itself. A transcribed total obeys the same
    // below-zero preference as a played one, so registering a past game can't be
    // a back door past the user's choice.
    fun finalScoreEntry(
        participantId: Long,
        points: Int,
        playedAt: Instant,
        allowNegativeScores: Boolean = false,
    ): ScoreEntryEntity =
        ScoreEntryEntity(
            participantId = participantId,
            points = NegativeScores.clamped(points, allowNegativeScores),
            timestamp = playedAt,
        )
}
