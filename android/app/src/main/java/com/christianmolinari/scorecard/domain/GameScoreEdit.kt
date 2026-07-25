package com.christianmolinari.scorecard.domain

import com.christianmolinari.scorecard.data.db.GameEditEntity
import com.christianmolinari.scorecard.data.db.ScoreEntryEntity
import java.time.Instant

// Editing a finished game: the arithmetic behind what total can actually be
// stored, what score entry moves a competitor to it, and whether anything
// changed at all. Port of the iOS GameScoreEdit enum and Game.applyScoreEdit;
// see docs/game-editing.md for the cross-platform contract.
//
// Like GameRegistration these are pure builders, so the semantics unit-test on
// the JVM; the screen performs the DAO writes (through GameDao.applyScoreEdit,
// which puts them in one transaction).
object GameScoreEdit {

    // The total actually storable for a requested new total.
    //
    // The app-wide below-zero preference (see NegativeScores and
    // docs/scoring-rules.md) applies to corrections exactly as it does to live
    // scoring: with it off, a total can't be driven below zero, so anything
    // negative lands on zero instead.
    fun normalizedTotal(requested: Int, allowNegative: Boolean): Int =
        NegativeScores.clamped(requested, allowNegative)

    // The total to propose for a field the user has typed into.
    //
    // The clamp belongs here and ONLY here: it must apply to a value the user
    // supplied, never to a total merely read back out of the store. A game can
    // legitimately hold a negative total (played while the preference was on);
    // clamping it just because it was displayed would propose a change nobody
    // made — arming Save with no input and rewriting a finished score.
    //
    // An empty or half-typed field ("", "-") falls back to the untouched
    // original rather than to zero, so clearing a total never arms a save.
    fun typedTotal(text: String, fallback: Int, allowNegative: Boolean): Int =
        text.toIntOrNull()?.let { normalizedTotal(it, allowNegative) } ?: fallback

    // The score entry delta needed to move a competitor from one total to
    // another. A total is derived by summing the competitor's entries, so an
    // edit is applied by appending one more entry rather than by rewriting
    // history — this is the value that entry must carry.
    fun delta(from: Int, to: Int): Int = to - from

    // Whether a proposed set of totals differs from the current ones. Compared
    // element-wise in the same order (both lists come from the same participant
    // ordering). A game is only recorded as edited when the final score
    // actually moved, so this gates both the Save button and the commit.
    fun isChanged(before: List<Int>, after: List<Int>): Boolean = before != after

    // What a save actually writes: one appended entry per *changed* competitor,
    // plus the edit record carrying the reason.
    data class Plan(val entries: List<ScoreEntryEntity>, val edit: GameEditEntity)

    // The plan for correcting a game's scores, or null when nothing should be
    // written.
    //
    // Null covers both refusals, so a caller cannot accidentally record an edit
    // that didn't happen:
    // - the totals don't line up with the competitors (pairing them would write
    //   a correction to the wrong competitor's score);
    // - no total actually moved, which is the "a game is edited only if the
    //   final score is different" rule. Note this catches changing a total and
    //   putting it back, too.
    //
    // The game's closedAt is deliberately not part of this: an edit corrects a
    // finished game, it never reopens one.
    fun plan(
        gameId: Long,
        participantIds: List<Long>,
        originalTotals: List<Int>,
        proposedTotals: List<Int>,
        reason: String,
        editedAt: Instant,
    ): Plan? {
        if (participantIds.size != originalTotals.size) return null
        if (proposedTotals.size != participantIds.size) return null
        if (!isChanged(before = originalTotals, after = proposedTotals)) return null

        val entries = participantIds.indices.mapNotNull { index ->
            val points = delta(from = originalTotals[index], to = proposedTotals[index])
            if (points == 0) null
            else ScoreEntryEntity(
                participantId = participantIds[index],
                points = points,
                timestamp = editedAt,
            )
        }
        return Plan(
            entries = entries,
            edit = GameEditEntity(gameId = gameId, reason = reason, editedAt = editedAt),
        )
    }
}
