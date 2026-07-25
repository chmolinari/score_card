package com.christianmolinari.scorecard

import com.christianmolinari.scorecard.data.db.GameEditEntity
import com.christianmolinari.scorecard.data.db.GameEntity
import com.christianmolinari.scorecard.data.db.GameWithDetails
import com.christianmolinari.scorecard.data.db.ParticipantEntity
import com.christianmolinari.scorecard.data.db.ParticipantWithDetails
import com.christianmolinari.scorecard.data.db.ScoreEntryEntity
import com.christianmolinari.scorecard.domain.GameScoreEdit
import com.christianmolinari.scorecard.domain.isEdited
import com.christianmolinari.scorecard.domain.lastEditedAt
import com.christianmolinari.scorecard.domain.sortedEdits
import com.christianmolinari.scorecard.domain.totalScore
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Editing a closed game's scores: the arithmetic, the "only if the final score
// changed" rule, and how a correction is recorded. Mirrors the iOS
// ScoreCardTests editing section; see docs/game-editing.md.
class GameScoreEditTest {

    private val editedAt: Instant = Instant.parse("2026-07-25T18:00:00Z")

    // --- normalizedTotal: the app-wide below-zero policy ---

    @Test
    fun `normalized total clamps at zero when below-zero scores are disallowed`() {
        assertEquals(0, GameScoreEdit.normalizedTotal(-5, allowNegative = false))
        assertEquals(0, GameScoreEdit.normalizedTotal(0, allowNegative = false))
        assertEquals(7, GameScoreEdit.normalizedTotal(7, allowNegative = false))
    }

    @Test
    fun `normalized total passes negatives through when they are allowed`() {
        assertEquals(-5, GameScoreEdit.normalizedTotal(-5, allowNegative = true))
        assertEquals(7, GameScoreEdit.normalizedTotal(7, allowNegative = true))
    }

    // --- typedTotal: the clamp applies to typed values, never to stored ones ---

    @Test
    fun `a typed total is clamped but an untouched negative total is not`() {
        // The user typing "-5" gets the clamp...
        assertEquals(0, GameScoreEdit.typedTotal("-5", fallback = 12, allowNegative = false))
        assertEquals(-5, GameScoreEdit.typedTotal("-5", fallback = 12, allowNegative = true))

        // ...but a stored negative total is NOT a typed value. This is the
        // regression guard: clamping a total merely read back out of the store
        // proposes a change nobody made, which armed Save on arrival and rewrote
        // a finished score to zero on the next tap.
        val storedNegative = -3
        val untouched = GameScoreEdit.isChanged(
            before = listOf(12, storedNegative),
            after = listOf(12, storedNegative),
        )
        assertFalse(untouched)
    }

    @Test
    fun `an empty or half-typed field falls back to the untouched original`() {
        assertEquals(12, GameScoreEdit.typedTotal("", fallback = 12, allowNegative = false))
        assertEquals(12, GameScoreEdit.typedTotal("-", fallback = 12, allowNegative = false))
        // Even when the original is itself negative, clearing the field must
        // leave it exactly as it was rather than clamping it to zero.
        assertEquals(-3, GameScoreEdit.typedTotal("", fallback = -3, allowNegative = false))
    }

    // --- delta: the entry that moves a competitor to a new total ---

    @Test
    fun `delta moves a total in both directions`() {
        assertEquals(-2, GameScoreEdit.delta(from = 11, to = 9))
        assertEquals(4, GameScoreEdit.delta(from = 7, to = 11))
        assertEquals(0, GameScoreEdit.delta(from = 11, to = 11))
        assertEquals(-14, GameScoreEdit.delta(from = 11, to = -3))
    }

    // --- isChanged: requirement 3, in its purest form ---

    @Test
    fun `change is detected only when a total actually differs`() {
        assertFalse(GameScoreEdit.isChanged(before = listOf(11, 7), after = listOf(11, 7)))
        assertFalse(GameScoreEdit.isChanged(before = emptyList(), after = emptyList()))
        assertTrue(GameScoreEdit.isChanged(before = listOf(11, 7), after = listOf(9, 7)))
        assertTrue(GameScoreEdit.isChanged(before = listOf(11, 7), after = listOf(11, 8)))
    }

    // --- plan: what a save actually writes ---

    @Test
    fun `plan appends one delta entry per changed competitor and records the reason`() {
        val plan = GameScoreEdit.plan(
            gameId = 42,
            participantIds = listOf(1, 2),
            originalTotals = listOf(11, 7),
            proposedTotals = listOf(9, 7),
            reason = "Miscounted the last scopa",
            editedAt = editedAt,
        )

        assertTrue(plan != null)
        // Only Alice moved, so only Alice gets an entry — no zero-point rows.
        assertEquals(1, plan!!.entries.size)
        assertEquals(1L, plan.entries[0].participantId)
        assertEquals(-2, plan.entries[0].points)
        assertEquals(editedAt, plan.entries[0].timestamp)

        assertEquals(42L, plan.edit.gameId)
        assertEquals("Miscounted the last scopa", plan.edit.reason)
        assertEquals(editedAt, plan.edit.editedAt)
    }

    @Test
    fun `the delta lands the competitor exactly on the requested total`() {
        val original = 11
        val requested = 9
        val existing = listOf(entry(id = 1, participantId = 1, points = original))

        val plan = GameScoreEdit.plan(
            gameId = 42,
            participantIds = listOf(1),
            originalTotals = listOf(original),
            proposedTotals = listOf(requested),
            reason = "Correction",
            editedAt = editedAt,
        )

        // History is appended to, never rewritten: the competitor keeps its
        // original entry and gains the adjustment.
        val after = participant(id = 1, entries = existing + plan!!.entries)
        assertEquals(2, after.entries.size)
        assertEquals(requested, after.totalScore)
    }

    @Test
    fun `an unchanged final score records nothing at all`() {
        val plan = GameScoreEdit.plan(
            gameId = 42,
            participantIds = listOf(1, 2),
            originalTotals = listOf(61, 59),
            proposedTotals = listOf(61, 59),
            reason = "Should never be recorded",
            editedAt = editedAt,
        )
        assertNull(plan)
    }

    @Test
    fun `totals that do not line up with the competitors are refused`() {
        // Pairing these would write a correction to the wrong competitor.
        assertNull(
            GameScoreEdit.plan(
                gameId = 42,
                participantIds = listOf(1),
                originalTotals = listOf(11),
                proposedTotals = listOf(9, 4),
                reason = "Two totals, one competitor",
                editedAt = editedAt,
            )
        )
    }

    // --- the game's own view of having been edited ---

    @Test
    fun `a game with no edits reads as never edited`() {
        val game = game(edits = emptyList())
        assertFalse(game.isEdited)
        assertNull(game.lastEditedAt)
    }

    @Test
    fun `edit history is ordered newest first`() {
        val older = GameEditEntity(
            id = 1,
            gameId = 42,
            reason = "First correction",
            editedAt = Instant.parse("2026-07-20T10:00:00Z"),
        )
        val newer = GameEditEntity(
            id = 2,
            gameId = 42,
            reason = "Second correction",
            editedAt = Instant.parse("2026-07-24T10:00:00Z"),
        )
        // Deliberately stored oldest-first, so the ordering is the property
        // under test rather than the insertion order.
        val game = game(edits = listOf(older, newer))

        assertTrue(game.isEdited)
        assertEquals(
            listOf("Second correction", "First correction"),
            game.sortedEdits.map { it.reason },
        )
        assertEquals(newer.editedAt, game.lastEditedAt)
    }

    // --- helpers ---

    private fun entry(id: Long, participantId: Long, points: Int) =
        ScoreEntryEntity(id = id, participantId = participantId, points = points, timestamp = editedAt)

    private fun participant(id: Long, entries: List<ScoreEntryEntity>) =
        ParticipantWithDetails(
            participant = ParticipantEntity(
                id = id,
                gameId = 42,
                nameSnapshot = "Alice",
                sortIndex = 0,
            ),
            entries = entries,
            player = null,
            team = null,
        )

    private fun game(edits: List<GameEditEntity>) =
        GameWithDetails(
            game = GameEntity(
                id = 42,
                title = "Scopa",
                createdAt = Instant.parse("2026-07-19T10:00:00Z"),
                closedAt = Instant.parse("2026-07-19T11:00:00Z"),
            ),
            participants = emptyList(),
            seats = emptyList(),
            edits = edits,
        )
}
