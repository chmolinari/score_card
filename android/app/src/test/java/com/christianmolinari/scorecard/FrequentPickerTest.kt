package com.christianmolinari.scorecard

import com.christianmolinari.scorecard.domain.FrequentPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Most-used ranking for the New Game selector. The picker only needs a usage
// count and a name, so a plain stand-in is enough — no database involved.
class FrequentPickerTest {

    private data class Item(val name: String, val usage: Int)

    @Test
    fun ranksByUsageThenName() {
        val items = listOf(
            Item(name = "Zoe", usage = 5),
            Item(name = "Amy", usage = 5),   // ties with Zoe → name breaks tie
            Item(name = "Bob", usage = 9),
            Item(name = "Cal", usage = 0),   // unused → excluded
            Item(name = "Dan", usage = 1),
            Item(name = "Eve", usage = 2),
            Item(name = "Fox", usage = 3),
        )

        val top = FrequentPicker.top(items, 5, { it.usage }, { it.name })

        assertEquals(listOf("Bob", "Amy", "Zoe", "Fox", "Eve"), top.map { it.name })
        assertEquals(5, top.size)                          // capped at the limit
        assertFalse(top.any { it.name == "Cal" })          // zero-usage excluded
    }

    @Test
    fun zeroUsageItemsAreExcludedEvenWhenThereIsRoom() {
        val items = listOf(
            Item(name = "Used", usage = 1),
            Item(name = "Never", usage = 0),
        )

        val top = FrequentPicker.top(items, 5, { it.usage }, { it.name })

        assertEquals(listOf("Used"), top.map { it.name })
    }

    @Test
    fun limitIsHonored() {
        val items = (1..10).map { Item(name = "P$it", usage = it) }

        val top = FrequentPicker.top(items, 3, { it.usage }, { it.name })

        assertEquals(listOf("P10", "P9", "P8"), top.map { it.name })
    }

    @Test
    fun usageTiesBreakByNumericAwareName() {
        // Same usage everywhere: the order must be the name order, and the name
        // comparison treats digit runs as numbers ("Player 2" before "Player 10").
        val items = listOf(
            Item(name = "Player 10", usage = 4),
            Item(name = "Player 2", usage = 4),
            Item(name = "alice", usage = 4),    // case-insensitive: sorts with "A"
            Item(name = "Bob", usage = 4),
        )

        val top = FrequentPicker.top(items, 5, { it.usage }, { it.name })

        assertEquals(listOf("alice", "Bob", "Player 2", "Player 10"), top.map { it.name })
    }

    @Test
    fun allZeroUsageYieldsEmptyResult() {
        val items = listOf(Item(name = "A", usage = 0), Item(name = "B", usage = 0))

        assertTrue(FrequentPicker.top(items, 5, { it.usage }, { it.name }).isEmpty())
    }
}
