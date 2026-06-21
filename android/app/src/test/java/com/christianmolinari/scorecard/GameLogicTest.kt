package com.christianmolinari.scorecard

import com.christianmolinari.scorecard.data.db.GameEntity
import com.christianmolinari.scorecard.data.db.GameWithDetails
import com.christianmolinari.scorecard.data.db.ParticipantEntity
import com.christianmolinari.scorecard.data.db.ParticipantWithDetails
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.ScoreEntryEntity
import com.christianmolinari.scorecard.data.db.SeatEntity
import com.christianmolinari.scorecard.data.db.SeatWithPlayer
import com.christianmolinari.scorecard.data.db.TeamEntity
import com.christianmolinari.scorecard.data.db.TeamWithMembers
import com.christianmolinari.scorecard.domain.DealingDirection
import com.christianmolinari.scorecard.domain.Tally
import com.christianmolinari.scorecard.domain.advancedDealerIndex
import com.christianmolinari.scorecard.domain.currentDealer
import com.christianmolinari.scorecard.domain.displayName
import com.christianmolinari.scorecard.domain.hasSeating
import com.christianmolinari.scorecard.domain.isDraw
import com.christianmolinari.scorecard.domain.isDrawFor
import com.christianmolinari.scorecard.domain.isSoleWinner
import com.christianmolinari.scorecard.domain.leader
import com.christianmolinari.scorecard.domain.nextDealer
import com.christianmolinari.scorecard.domain.participantsInDealingOrder
import com.christianmolinari.scorecard.domain.playerTally
import com.christianmolinari.scorecard.domain.rankedParticipants
import com.christianmolinari.scorecard.domain.rankedScores
import com.christianmolinari.scorecard.domain.teamTally
import com.christianmolinari.scorecard.domain.topScorers
import com.christianmolinari.scorecard.domain.totalScore
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Domain-logic tests covering the scorekeeping requirements: scoring, ranking,
// draws, dealing rotation, and per-competitor tallies. The relation types are
// plain data classes, so everything is built by hand — no database involved.
class GameLogicTest {

    private val t0: Instant = Instant.parse("2026-06-01T18:30:00Z")

    private fun player(id: Long, name: String) =
        PlayerEntity(id = id, name = name, createdAt = t0)

    private fun soloParticipant(
        id: Long,
        gameId: Long,
        player: PlayerEntity,
        sortIndex: Int,
        points: List<Int> = emptyList(),
    ) = ParticipantWithDetails(
        participant = ParticipantEntity(
            id = id,
            gameId = gameId,
            playerId = player.id,
            nameSnapshot = player.name,
            sortIndex = sortIndex,
        ),
        entries = points.mapIndexed { i, value ->
            ScoreEntryEntity(
                id = id * 100 + i,
                participantId = id,
                points = value,
                timestamp = t0.plusSeconds(i.toLong()),
            )
        },
        player = player,
        team = null,
    )

    private fun teamParticipant(
        id: Long,
        gameId: Long,
        team: TeamWithMembers,
        sortIndex: Int,
        points: List<Int> = emptyList(),
    ) = ParticipantWithDetails(
        participant = ParticipantEntity(
            id = id,
            gameId = gameId,
            teamId = team.team.id,
            nameSnapshot = team.team.name,
            sortIndex = sortIndex,
        ),
        entries = points.mapIndexed { i, value ->
            ScoreEntryEntity(
                id = id * 100 + i,
                participantId = id,
                points = value,
                timestamp = t0.plusSeconds(i.toLong()),
            )
        },
        player = null,
        team = team,
    )

    private fun seat(id: Long, gameId: Long, player: PlayerEntity, position: Int) =
        SeatWithPlayer(
            seat = SeatEntity(id = id, gameId = gameId, playerId = player.id, position = position),
            player = player,
        )

    private fun game(
        id: Long,
        title: String,
        participants: List<ParticipantWithDetails>,
        seats: List<SeatWithPlayer> = emptyList(),
        closedAt: Instant? = null,
        currentDealerIndex: Int = 0,
    ) = GameWithDetails(
        game = GameEntity(
            id = id,
            title = title,
            createdAt = t0,
            closedAt = closedAt,
            currentDealerIndex = currentDealerIndex,
        ),
        participants = participants,
        seats = seats,
    )

    // MARK-equivalent: ranking is highest-first; equal scores keep added order.

    @Test
    fun rankedScoresOrderHighestFirstWithSortIndexTieBreak() {
        val alice = player(1, "Alice")
        val bob = player(2, "Bob")
        val carol = player(3, "Carol")
        val pa = soloParticipant(10, 1, alice, 0, listOf(3, 5))   // 8
        val pb = soloParticipant(11, 1, bob, 1, listOf(4))        // 4
        val pc = soloParticipant(12, 1, carol, 2, listOf(8))      // 8, ties Alice → sortIndex breaks it

        assertEquals(8, pa.totalScore)
        assertEquals(4, pb.totalScore)

        // Hand the participants over out of order to prove the sort does the work.
        val g = game(1, "Scopa", listOf(pb, pc, pa))

        assertEquals(listOf("Alice", "Carol", "Bob"), g.rankedParticipants.map { it.displayName })
        assertEquals(listOf(8, 8, 4), g.rankedScores.map { it.second })
        assertEquals("Alice", g.leader?.displayName)
    }

    @Test
    fun openGameIsNeverADraw() {
        val a = player(1, "A")
        val b = player(2, "B")
        val pa = soloParticipant(10, 1, a, 0, listOf(10))
        val pb = soloParticipant(11, 1, b, 1, listOf(10))
        val g = game(1, "Tie", listOf(pa, pb))   // still open: closedAt == null

        // A shared top score only becomes a draw once the game is closed.
        assertFalse(g.isDraw)
        assertFalse(g.isDrawFor(pa))
        assertFalse(g.isSoleWinner(pa))
    }

    @Test
    fun closedTieIsDrawForTopScorersOnly() {
        val a = player(1, "A")
        val b = player(2, "B")
        val c = player(3, "C")
        // A and B tie for the top at 10; C trails at 4.
        val pa = soloParticipant(10, 1, a, 0, listOf(10))
        val pb = soloParticipant(11, 1, b, 1, listOf(10))
        val pc = soloParticipant(12, 1, c, 2, listOf(4))
        val g = game(1, "Three-way", listOf(pa, pb, pc), closedAt = t0.plusSeconds(3600))

        assertTrue(g.isDraw)
        assertEquals(listOf("A", "B"), g.topScorers.map { it.displayName })
        // The two leaders drew; the trailing player neither won nor drew.
        assertTrue(g.isDrawFor(pa))
        assertTrue(g.isDrawFor(pb))
        assertFalse(g.isDrawFor(pc))
        assertFalse(g.isSoleWinner(pa))
        assertFalse(g.isSoleWinner(pc))
    }

    @Test
    fun soleWinnerNeedsAClosedGameAndASingleTopScore() {
        val alice = player(1, "Alice")
        val bob = player(2, "Bob")
        val pa = soloParticipant(10, 1, alice, 0, listOf(11))
        val pb = soloParticipant(11, 1, bob, 1, listOf(7))

        val open = game(1, "Scopa", listOf(pa, pb))
        assertFalse(open.isSoleWinner(pa))   // still open → nobody has won yet

        val closed = game(1, "Scopa", listOf(pa, pb), closedAt = t0.plusSeconds(3600))
        assertTrue(closed.isSoleWinner(pa))
        assertFalse(closed.isSoleWinner(pb))
        assertFalse(closed.isDraw)
    }

    // MARK-equivalent: scoreboard order follows the dealing rotation, not the score.

    @Test
    fun participantsOrderByDealingRotation() {
        val players = listOf("A", "B", "C", "D").mapIndexed { i, name -> player((i + 1).toLong(), name) }
        val parts = players.mapIndexed { i, p ->
            // Give the trailing seat the highest score to prove order ignores it.
            soloParticipant((10 + i).toLong(), 1, p, i, if (i == 3) listOf(99) else emptyList())
        }
        val seats = players.mapIndexed { i, p -> seat((20 + i).toLong(), 1, p, i) }
        val g = game(1, "Briscola", parts, seats)

        // Counter-clockwise keeps the seat order: first dealer (A) on top.
        assertEquals(
            listOf("A", "B", "C", "D"),
            g.participantsInDealingOrder(DealingDirection.CounterClockwise).map { it.displayName })
        // Clockwise: first dealer still on top, the rest follow the deal backwards.
        assertEquals(
            listOf("A", "D", "C", "B"),
            g.participantsInDealingOrder(DealingDirection.Clockwise).map { it.displayName })
    }

    @Test
    fun teamsOrderByEarliestDealingMember() {
        val players = listOf("A", "B", "C", "D").mapIndexed { i, name -> player((i + 1).toLong(), name) }
        // Two teams seated alternately: Blues are A and C, Reds are B and D.
        val blues = TeamWithMembers(
            team = TeamEntity(id = 1, name = "Blues", createdAt = t0),
            members = listOf(players[0], players[2]),
        )
        val reds = TeamWithMembers(
            team = TeamEntity(id = 2, name = "Reds", createdAt = t0),
            members = listOf(players[1], players[3]),
        )
        val parts = listOf(
            teamParticipant(10, 1, blues, 0),
            teamParticipant(11, 1, reds, 1),
        )
        val seats = players.mapIndexed { i, p -> seat((20 + i).toLong(), 1, p, i) }
        val g = game(1, "Tressette", parts, seats)

        // First dealer is A (Blues), so Blues is on top counter-clockwise.
        assertEquals(
            listOf("Blues", "Reds"),
            g.participantsInDealingOrder(DealingDirection.CounterClockwise).map { it.displayName })
        // Clockwise the deal goes A → D(Reds) next, so Reds' earliest member (D)
        // outranks Blues' next member (C): the team with A still leads, though.
        assertEquals(
            listOf("Blues", "Reds"),
            g.participantsInDealingOrder(DealingDirection.Clockwise).map { it.displayName })
    }

    @Test
    fun dealingOrderFallsBackToAddedOrderWithoutSeating() {
        val alice = player(1, "Alice")
        val bob = player(2, "Bob")
        val pa = soloParticipant(10, 1, alice, 0)
        val pb = soloParticipant(11, 1, bob, 1, listOf(50))   // higher score must not reorder
        val g = game(1, "Briscola", listOf(pa, pb))

        assertEquals(
            listOf("Alice", "Bob"),
            g.participantsInDealingOrder(DealingDirection.CounterClockwise).map { it.displayName })
    }

    // MARK-equivalent: dealer / seating.

    @Test
    fun advancedDealerIndexWrapsBothWays() {
        val players = listOf("A", "B", "C", "D").mapIndexed { i, name -> player((i + 1).toLong(), name) }
        val parts = players.mapIndexed { i, p -> soloParticipant((10 + i).toLong(), 1, p, i) }
        val seats = players.mapIndexed { i, p -> seat((20 + i).toLong(), 1, p, i) }
        val g = game(1, "Briscola", parts, seats, currentDealerIndex = 0)

        assertTrue(g.hasSeating)
        assertEquals("A", g.currentDealer?.name)
        assertEquals("B", g.nextDealer(DealingDirection.CounterClockwise)?.name)
        assertEquals("D", g.nextDealer(DealingDirection.Clockwise)?.name)   // clockwise = previous seat

        assertEquals(1, g.advancedDealerIndex(DealingDirection.CounterClockwise))
        assertEquals(3, g.advancedDealerIndex(DealingDirection.Clockwise))   // wraps backwards

        val atLastSeat = g.copy(game = g.game.copy(currentDealerIndex = 3))
        assertEquals("D", atLastSeat.currentDealer?.name)
        assertEquals(0, atLastSeat.advancedDealerIndex(DealingDirection.CounterClockwise))   // wraps around the table
        assertEquals(2, atLastSeat.advancedDealerIndex(DealingDirection.Clockwise))
    }

    @Test
    fun gameWithoutSeatingHasNoDealer() {
        val g = game(1, "Scopa", emptyList())
        assertFalse(g.hasSeating)
        assertNull(g.currentDealer)
        assertNull(g.nextDealer(DealingDirection.CounterClockwise))
    }

    // MARK-equivalent: tally — win/play record per player and team.

    @Test
    fun playerTallyCountsWinsPlaysAndInProgress() {
        val alice = player(1, "Alice")
        val bob = player(2, "Bob")

        // Finished game 1: Alice beats Bob.
        val g1 = game(
            1, "Scopa",
            listOf(
                soloParticipant(10, 1, alice, 0, listOf(11)),
                soloParticipant(11, 1, bob, 1, listOf(7)),
            ),
            closedAt = t0.plusSeconds(3600),
        )
        // Finished game 2: Bob beats Alice.
        val g2 = game(
            2, "Briscola",
            listOf(
                soloParticipant(20, 2, alice, 0, listOf(40)),
                soloParticipant(21, 2, bob, 1, listOf(81)),
            ),
            closedAt = t0.plusSeconds(7200),
        )
        // Open game: counts only as in-progress for both.
        val g3 = game(
            3, "Open one",
            listOf(
                soloParticipant(30, 3, alice, 0),
                soloParticipant(31, 3, bob, 1),
            ),
        )
        val games = listOf(g1, g2, g3)

        val aliceTally = playerTally(alice.id, games)
        assertEquals(2, aliceTally.played)
        assertEquals(1, aliceTally.won)
        assertEquals(0, aliceTally.drawn)
        assertEquals(1, aliceTally.inProgress)
        assertEquals(50, aliceTally.winPercentage)

        val bobTally = playerTally(bob.id, games)
        assertEquals(2, bobTally.played)
        assertEquals(1, bobTally.won)
        assertEquals(1, bobTally.inProgress)
    }

    @Test
    fun teamTallyCountsTiesAsDrawForBoth() {
        val red = TeamWithMembers(team = TeamEntity(id = 1, name = "Red", createdAt = t0), members = emptyList())
        val blue = TeamWithMembers(team = TeamEntity(id = 2, name = "Blue", createdAt = t0), members = emptyList())
        val g = game(
            1, "Tie",
            listOf(
                teamParticipant(10, 1, red, 0, listOf(10)),
                teamParticipant(11, 1, blue, 1, listOf(10)),
            ),
            closedAt = t0.plusSeconds(3600),
        )

        // A shared top score is a draw, not a win, for everyone tied.
        assertTrue(g.isDraw)
        val redTally = teamTally(red.team.id, listOf(g))
        assertEquals(0, redTally.won)
        assertEquals(1, redTally.drawn)
        assertEquals(1, redTally.played)
        val blueTally = teamTally(blue.team.id, listOf(g))
        assertEquals(0, blueTally.won)
        assertEquals(1, blueTally.drawn)
    }

    @Test
    fun drawOnlyCountsForTiedTopScorers() {
        val a = player(1, "A")
        val b = player(2, "B")
        val c = player(3, "C")
        // A and B tie for the top at 10; C trails at 4.
        val g = game(
            1, "Three-way",
            listOf(
                soloParticipant(10, 1, a, 0, listOf(10)),
                soloParticipant(11, 1, b, 1, listOf(10)),
                soloParticipant(12, 1, c, 2, listOf(4)),
            ),
            closedAt = t0.plusSeconds(3600),
        )

        assertTrue(g.isDraw)
        // The two leaders drew; the trailing player neither won nor drew.
        assertEquals(1, playerTally(a.id, listOf(g)).drawn)
        assertEquals(1, playerTally(b.id, listOf(g)).drawn)
        val cTally = playerTally(c.id, listOf(g))
        assertEquals(0, cTally.drawn)
        assertEquals(0, cTally.won)
        assertEquals(1, cTally.played)
    }

    @Test
    fun emptyTallyForPlayerWithNoGames() {
        val tally = playerTally(99, emptyList())
        assertTrue(tally.isEmpty)
        assertNull(tally.winPercentage)
        assertEquals(Tally(), tally)
    }
}
