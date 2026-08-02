package com.christianmolinari.scorecard

import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.TeamEntity
import com.christianmolinari.scorecard.data.db.TeamWithMembers
import com.christianmolinari.scorecard.domain.GameCompetitor
import com.christianmolinari.scorecard.domain.RosterCheck
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The roster guards, ported from the iOS RosterCheck tests. Kept in step with
// ScoreCardTests.swift — a team needs two members to be saved or played, but a
// smaller one can still arrive from an older backup and must not be rejected.
class RosterCheckTest {

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun player(id: Long, name: String) = PlayerEntity(id = id, name = name, createdAt = t0)

    private fun team(id: Long, name: String, members: List<PlayerEntity>) = TeamWithMembers(
        team = TeamEntity(id = id, name = name, createdAt = t0),
        members = members,
    )

    @Test
    fun deletingAPlayerReportsEveryTeamItWouldShrink() {
        val alice = player(1, "Alice")
        val bob = player(2, "Bob")
        val carol = player(3, "Carol")
        // Aces drops to one member without Alice; Trio still has two.
        val teams = listOf(
            team(1, "Aces", listOf(alice, bob)),
            team(2, "Trio", listOf(alice, bob, carol)),
            team(3, "Others", listOf(bob, carol)),
        )

        val impacts = RosterCheck.impactOfDeleting(alice, teams)
        assertEquals(
            listOf(
                RosterCheck.TeamImpact("Aces", 1),
                RosterCheck.TeamImpact("Trio", 2),
            ),
            impacts,
        )
        assertEquals(listOf(true, false), impacts.map { it.fallsBelowMinimum })
    }

    @Test
    fun aPlayerOnNoTeamsReportsNoImpact() {
        val solo = player(1, "Solo")
        assertTrue(RosterCheck.impactOfDeleting(solo, emptyList()).isEmpty())
        assertTrue(
            RosterCheck.playerDeletionMessage("Solo", emptyList())
                .contains("Past game results are not affected"),
        )
    }

    @Test
    fun underStrengthFlagsTeamsTooSmallToPlay() {
        val alice = player(1, "Alice")
        val bob = player(2, "Bob")
        val pair = team(1, "Pair", listOf(alice, bob))
        val single = team(2, "Single", listOf(alice))
        val empty = team(3, "Empty", emptyList())

        assertFalse(RosterCheck.isUnderStrength(pair))
        assertTrue(RosterCheck.isUnderStrength(single))
        assertTrue(RosterCheck.isUnderStrength(empty))

        // A player competitor is never under strength, whatever else is chosen.
        val names = RosterCheck.underStrengthNames(
            listOf(
                GameCompetitor.TeamCompetitor(pair),
                GameCompetitor.TeamCompetitor(single),
                GameCompetitor.TeamCompetitor(empty),
                GameCompetitor.PlayerCompetitor(alice),
            ),
        )
        assertEquals(listOf("Single", "Empty"), names)
    }

    @Test
    fun deletionMessageNamesTheTeamsLeftUnplayable() {
        val message = RosterCheck.playerDeletionMessage(
            "Adriano",
            listOf(
                RosterCheck.TeamImpact("Adriano e Christian", 1),
                RosterCheck.TeamImpact("Adriano e Bassano", 1),
            ),
        )
        assertTrue(message.contains("Adriano e Christian and Adriano e Bassano"))
        assertTrue(message.contains("they can't be picked for a game"))

        // One broken team reads in the singular.
        val single = RosterCheck.playerDeletionMessage(
            "Adriano",
            listOf(RosterCheck.TeamImpact("Adriano e Christian", 1)),
        )
        assertTrue(single.contains("it can't be picked for a game"))

        // Nothing dropping below the minimum means no warning sentence at all.
        val intact = RosterCheck.playerDeletionMessage(
            "Adriano",
            listOf(RosterCheck.TeamImpact("Trio", 2)),
        )
        assertFalse(intact.contains("picked for a game"))
    }

    @Test
    fun teamDeletionMessageSaysThePlayersSurvive() {
        val message = RosterCheck.teamDeletionMessage("Adriano e Christian", memberCount = 2)
        assertTrue(message.contains("Its 2 members stay on the Players tab"))
        assertTrue(message.contains("Past game results are not affected"))

        assertTrue(
            RosterCheck.teamDeletionMessage("Solo Team", memberCount = 1)
                .contains("Its 1 member stays on the Players tab"),
        )
    }
}
