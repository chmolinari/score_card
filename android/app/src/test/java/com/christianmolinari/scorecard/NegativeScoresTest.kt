package com.christianmolinari.scorecard

import com.christianmolinari.scorecard.domain.NegativeScores
import org.junit.Assert.assertEquals
import org.junit.Test

// Below-zero scoring policy (port of the iOS NegativeScores tests).
class NegativeScoresTest {

    @Test
    fun clampStopsAtZero() {
        // Additions always pass through, whatever the policy.
        assertEquals(3, NegativeScores.effectiveDelta(points = 3, currentTotal = 0, allowNegative = false))
        assertEquals(3, NegativeScores.effectiveDelta(points = 3, currentTotal = 5, allowNegative = true))

        // Clamping (default): a subtraction is reduced so the total stops at zero.
        assertEquals(-2, NegativeScores.effectiveDelta(points = -5, currentTotal = 2, allowNegative = false))
        // Exact subtraction down to zero is unchanged.
        assertEquals(-2, NegativeScores.effectiveDelta(points = -2, currentTotal = 2, allowNegative = false))
        // Already at zero: subtraction is dropped entirely (never flips to an addition).
        assertEquals(0, NegativeScores.effectiveDelta(points = -5, currentTotal = 0, allowNegative = false))
        assertEquals(0, NegativeScores.effectiveDelta(points = -5, currentTotal = -3, allowNegative = false))

        // When below-zero is allowed, subtraction passes through unchanged.
        assertEquals(-5, NegativeScores.effectiveDelta(points = -5, currentTotal = 2, allowNegative = true))
    }
}
