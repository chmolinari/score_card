package com.christianmolinari.scorecard

import com.christianmolinari.scorecard.data.db.DISTANT_PAST
import com.christianmolinari.scorecard.data.db.GameNameEntity
import com.christianmolinari.scorecard.domain.GameNamePicker
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Editable game-name list: the default pre-selection is the most recently used
// name; never-used names (all sharing DISTANT_PAST) and ties fall back to
// alphabetical order.
class GameNamePickerTest {

    private fun gameName(name: String, lastUsedAt: Instant = DISTANT_PAST) = GameNameEntity(
        name = name,
        createdAt = Instant.parse("2026-06-01T18:30:00Z"),
        lastUsedAt = lastUsedAt,
    )

    @Test
    fun defaultSelectionPicksMostRecentlyUsed() {
        val scopa = gameName("Scopa", lastUsedAt = Instant.ofEpochSecond(100))
        val briscola = gameName("Briscola", lastUsedAt = Instant.ofEpochSecond(500))
        val tresette = gameName("Tresette")   // never used

        val pick = GameNamePicker.defaultSelection(
            listOf(scopa, briscola, tresette),
            { it.lastUsedAt },
            { it.name },
        )

        assertEquals("Briscola", pick?.name)
    }

    @Test
    fun allUnusedFallsBackToAlphabetical() {
        // Never-used names all share DISTANT_PAST, so the tie-break decides.
        val zilch = gameName("Zilch")
        val alpha = gameName("Alpha")

        val pick = GameNamePicker.defaultSelection(
            listOf(zilch, alpha),
            { it.lastUsedAt },
            { it.name },
        )

        assertEquals("Alpha", pick?.name)
    }

    @Test
    fun lastUsedTiesFallBackToAlphabetical() {
        val sameInstant = Instant.ofEpochSecond(700)
        val zeta = gameName("Zeta", lastUsedAt = sameInstant)
        val beta = gameName("Beta", lastUsedAt = sameInstant)

        val pick = GameNamePicker.defaultSelection(
            listOf(zeta, beta),
            { it.lastUsedAt },
            { it.name },
        )

        assertEquals("Beta", pick?.name)
    }

    @Test
    fun emptyListYieldsNull() {
        assertNull(
            GameNamePicker.defaultSelection(
                emptyList<GameNameEntity>(),
                { it.lastUsedAt },
                { it.name },
            )
        )
    }
}
