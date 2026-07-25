package com.christianmolinari.scorecard

import com.christianmolinari.scorecard.data.db.GameWithDetails
import com.christianmolinari.scorecard.data.db.ParticipantWithDetails
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.TeamEntity
import com.christianmolinari.scorecard.data.db.TeamWithMembers
import com.christianmolinari.scorecard.domain.CompetitorSelectionRules
import com.christianmolinari.scorecard.domain.GameCompetitor
import com.christianmolinari.scorecard.domain.GameRegistration
import com.christianmolinari.scorecard.domain.isDraw
import com.christianmolinari.scorecard.domain.isDrawFor
import com.christianmolinari.scorecard.domain.isOpen
import com.christianmolinari.scorecard.domain.isSoleWinner
import com.christianmolinari.scorecard.domain.playerTally
import com.christianmolinari.scorecard.domain.rankedParticipants
import com.christianmolinari.scorecard.domain.totalScore
import com.christianmolinari.scorecard.ui.components.GameFormatting
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Tests for registering a past game: the played-on date/time opt-out
// semantics, the backdated-closed-game construction, and how a registered
// game feeds the shared ranking/draw/tally logic. The relation types are
// plain data classes, so everything is built by hand — no database involved.
class GameRegistrationTest {

    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private val now: Instant = Instant.parse("2026-07-12T15:00:00Z")
    private val playedAt: Instant = Instant.parse("2025-05-04T20:15:00Z")

    private fun player(id: Long, name: String) =
        PlayerEntity(id = id, name = name, createdAt = now)

    // A registered game wrapped in the read-side relation graph, one final
    // total per player, so the shared domain extensions can be exercised.
    private fun registeredGame(finals: List<Pair<PlayerEntity, Int>>): GameWithDetails {
        val game = GameRegistration.game(
            title = "Scopa",
            playedAt = playedAt,
            locationName = null,
            playedDateOnly = false,
        ).copy(id = 1)
        val participants = finals.mapIndexed { index, (player, points) ->
            val competitor = GameCompetitor.PlayerCompetitor(player)
            // Row ids are handed out by the database on insert; simulate that
            // here so identity-based checks (sole winner) work as they would live.
            val participant = GameRegistration.participant(game.id, competitor, index)
                .copy(id = index + 1L)
            ParticipantWithDetails(
                participant = participant,
                entries = listOf(
                    GameRegistration.finalScoreEntry(
                        participantId = participant.id,
                        points = points,
                        playedAt = playedAt,
                        allowNegativeScores = false,
                    )
                ),
                player = player,
                team = null,
            )
        }
        return GameWithDetails(game = game, participants = participants, seats = emptyList())
    }

    // MARK: Played-on date and time opt-outs

    @Test
    fun playedInstantHonorsDateAndTimeOptOuts() {
        val date = LocalDate.of(2025, 5, 4)
        val time = LocalTime.of(20, 15)

        // Date and time: taken verbatim (in the given zone).
        assertEquals(
            date.atTime(time).atZone(zone).toInstant(),
            GameRegistration.playedInstant(date = date, time = time, zone = zone, now = now),
        )

        // Date only: local midnight of that day, the "time unknown" marker.
        assertEquals(
            date.atStartOfDay(zone).toInstant(),
            GameRegistration.playedInstant(date = date, time = null, zone = zone, now = now),
        )

        // No date: the moment of registration, so the game files under today.
        assertEquals(
            now,
            GameRegistration.playedInstant(date = null, time = null, zone = zone, now = now),
        )
    }

    @Test
    fun playedInstantNeverReturnsAFutureStamp() {
        // Today's date combined with a time of day that hasn't happened yet
        // clamps to "now" instead of stamping a game in the future.
        val today = now.atZone(zone).toLocalDate()
        val futureTime = now.atZone(zone).toLocalTime().plusHours(2)
        assertEquals(
            now,
            GameRegistration.playedInstant(date = today, time = futureTime, zone = zone, now = now),
        )
    }

    // MARK: The registered game itself

    @Test
    fun registeredGameIsClosedAndBackdated() {
        val game = registeredGame(
            listOf(player(1, "Alice") to 21, player(2, "Bob") to 15)
        )

        assertFalse(game.isOpen)
        assertEquals(playedAt, game.game.createdAt)
        assertEquals(playedAt, game.game.closedAt)
        assertFalse(game.game.hasTarget)
        assertNull(game.game.targetPoints)
        assertNull(game.game.latitude)
        assertNull(game.game.longitude)
        // Not asserted here: that the game has no seats and exactly one entry
        // per competitor. `registeredGame` constructs the relation graph by
        // hand, so both would only read back the helper's own literals — the
        // builders under test have no seat or entry-count concept at all.
        // Each competitor's entry is stamped with the played-on instant.
        for (participant in game.participants) {
            assertEquals(playedAt, participant.entries.single().timestamp)
        }
    }

    @Test
    fun registeredGameRanksWinnersAndFeedsTallies() {
        val alice = player(1, "Alice")
        val bob = player(2, "Bob")
        val game = registeredGame(listOf(alice to 21, bob to 15))

        assertEquals(
            listOf("Alice", "Bob"),
            game.rankedParticipants.map { it.participant.nameSnapshot },
        )
        assertTrue(game.isSoleWinner(game.rankedParticipants[0]))
        assertFalse(game.isSoleWinner(game.rankedParticipants[1]))

        val aliceTally = playerTally(alice.id, listOf(game))
        assertEquals(1, aliceTally.played)
        assertEquals(1, aliceTally.won)
        val bobTally = playerTally(bob.id, listOf(game))
        assertEquals(1, bobTally.played)
        assertEquals(0, bobTally.won)
    }

    @Test
    fun registeredGameTiedFinalsCountAsDraw() {
        val game = registeredGame(
            listOf(player(1, "Alice") to 15, player(2, "Bob") to 15)
        )

        assertTrue(game.isDraw)
        for (participant in game.participants) {
            assertFalse(game.isSoleWinner(participant))
            assertTrue(game.isDrawFor(participant))
        }
    }

    @Test
    fun registeredGameFollowsTheBelowZeroPreference() {
        // A transcribed total obeys the same preference as a played one, so
        // registering defaults to clamping: the -5 lands on 0 and both
        // competitors tie there.
        val clamped = registeredGame(
            listOf(player(1, "Alice") to 0, player(2, "Bob") to -5)
        )
        assertEquals(0, clamped.participants[0].totalScore)
        assertEquals(0, clamped.participants[1].totalScore)
        assertFalse(clamped.isSoleWinner(clamped.rankedParticipants[0]))
        assertTrue(clamped.isDraw)

        // With below-zero allowed, the transcribed total is kept verbatim.
        assertEquals(
            -5,
            GameRegistration.finalScoreEntry(
                participantId = 1,
                points = -5,
                playedAt = playedAt,
                allowNegativeScores = true,
            ).points,
        )
    }

    @Test
    fun registeredGameNormalizesLocation() {
        assertNull(GameRegistration.game("Scopa", playedAt, locationName = null, playedDateOnly = false).locationName)
        assertNull(GameRegistration.game("Scopa", playedAt, locationName = "   ", playedDateOnly = false).locationName)
        assertEquals(
            "Nonna's place",
            GameRegistration.game("Scopa", playedAt, locationName = "  Nonna's place  ", playedDateOnly = false).locationName,
        )
    }

    // MARK: Competitor selection rules

    @Test
    fun competitorSelectionRulesEnforceTeamExclusivity() {
        val alice = GameCompetitor.PlayerCompetitor(player(1, "Alice"))
        val bob = GameCompetitor.PlayerCompetitor(player(2, "Bob"))
        val reds = GameCompetitor.TeamCompetitor(
            TeamWithMembers(
                team = TeamEntity(id = 1, name = "Reds", createdAt = now),
                members = emptyList(),
            )
        )

        // Selecting a team drops any individual players already chosen.
        val withTeam = CompetitorSelectionRules.toggling(reds, listOf(alice, bob))
        assertEquals(listOf<GameCompetitor>(reds), withTeam)

        // Adding an already-selected competitor is a no-op.
        assertEquals(listOf(alice, bob), CompetitorSelectionRules.adding(bob, listOf(alice, bob)))

        // Toggling a selected competitor removes it.
        assertEquals(listOf(alice), CompetitorSelectionRules.toggling(bob, listOf(alice, bob)))
    }

    // MARK: Display of the "time unknown" marker

    @Test
    fun dateTimeFormattingOmitsTimeAtExactMidnight() {
        // Exactly local midnight means "date known, time unknown" and renders
        // date-only; any other time of day renders date + time.
        val midnight = LocalDate.of(2025, 5, 4).atStartOfDay(zone).toInstant()
        assertEquals(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(zone).format(midnight),
            GameFormatting.dateTime(midnight, zone),
        )

        val evening = LocalDate.of(2025, 5, 4).atTime(20, 15).atZone(zone).toInstant()
        assertEquals(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withZone(zone).format(evening),
            GameFormatting.dateTime(evening, zone),
        )
    }

    // The played-on intent is stored, not inferred: a date-only game stays
    // date-only in any zone, and a deliberate 00:00 keeps its time. A row
    // written before the column existed leaves it null, and the formatter falls
    // back to the old start-of-day inference for those.
    @Test
    fun storedPlayedDateOnlyBeatsInferringFromTheStamp() {
        val midnight = LocalDate.of(2025, 5, 4).atStartOfDay(zone).toInstant()
        val dateOnlyText =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(zone).format(midnight)
        val withTimeText = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(zone).format(midnight)

        // Same instant either way — only the stored intent separates them.
        assertEquals(dateOnlyText, GameFormatting.dateTime(midnight, zone, dateOnly = true))
        assertEquals(withTimeText, GameFormatting.dateTime(midnight, zone, dateOnly = false))
        // Legacy row: inferred, as before.
        assertEquals(dateOnlyText, GameFormatting.dateTime(midnight, zone, dateOnly = null))

        // The builder records what the screen was told.
        assertEquals(
            true,
            GameRegistration.game("Scopa", midnight, null, playedDateOnly = true).playedDateOnly,
        )
    }

    @Test
    fun dateOnlyStampIsRecognizedAcrossADaylightSavingGap() {
        // America/Havana springs forward at 00:00, so 2025-03-09 has no local
        // midnight and atStartOfDay lands on 01:00. Comparing the stamp against
        // a literal LocalTime.MIDNIGHT would miss the "time unknown" marker and
        // render a time of day the user never gave.
        val havana = ZoneId.of("America/Havana")
        val date = LocalDate.of(2025, 3, 9)
        val stamp = GameRegistration.playedInstant(date = date, time = null, zone = havana, now = now)

        assertEquals(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(havana).format(stamp),
            GameFormatting.dateTime(stamp, havana),
        )
    }
}
