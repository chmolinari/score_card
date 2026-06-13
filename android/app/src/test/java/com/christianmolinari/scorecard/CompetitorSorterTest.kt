package com.christianmolinari.scorecard

import com.christianmolinari.scorecard.domain.CompetitorSortOrder
import com.christianmolinari.scorecard.domain.CompetitorSorter
import com.christianmolinari.scorecard.domain.Tally
import org.junit.Assert.assertEquals
import org.junit.Test

// Players/Teams list ordering.
class CompetitorSorterTest {

    // A stand-in for a player or team: the sorter only needs a name and a tally.
    private data class SortableCompetitor(val name: String, val tally: Tally)

    @Test
    fun ordersByNameInBothDirections() {
        val items = listOf(
            SortableCompetitor(name = "Bob", tally = Tally()),
            SortableCompetitor(name = "alice", tally = Tally()),      // lower-case sorts with "A"
            SortableCompetitor(name = "Player 10", tally = Tally()),
            SortableCompetitor(name = "Player 2", tally = Tally()),   // numeric-aware: 2 before 10
        )

        val ascending = CompetitorSorter.sorted(
            items, CompetitorSortOrder.NameAscending, { it.name }, { it.tally })
        assertEquals(listOf("alice", "Bob", "Player 2", "Player 10"), ascending.map { it.name })

        val descending = CompetitorSorter.sorted(
            items, CompetitorSortOrder.NameDescending, { it.name }, { it.tally })
        assertEquals(listOf("Player 10", "Player 2", "Bob", "alice"), descending.map { it.name })
    }

    @Test
    fun ranksByWinsThenWinPercentThenName() {
        val items = listOf(
            SortableCompetitor(name = "Cara", tally = Tally(played = 4, won = 2)),   // 2 wins, 50%
            SortableCompetitor(name = "Abe", tally = Tally(played = 10, won = 5)),   // 5 wins
            SortableCompetitor(name = "Bea", tally = Tally(played = 2, won = 2)),    // 2 wins, 100%
            SortableCompetitor(name = "Dan", tally = Tally(played = 4, won = 2)),    // ties Cara → name breaks it
            SortableCompetitor(name = "Eve", tally = Tally()),                       // no games → last
        )

        val descending = CompetitorSorter.sorted(
            items, CompetitorSortOrder.ScoreDescending, { it.name }, { it.tally })
        // Most wins first; within the 2-win group, higher win% first (Bea 100%
        // before the 50% pair), the 50% pair alphabetical; no-games last.
        assertEquals(listOf("Abe", "Bea", "Cara", "Dan", "Eve"), descending.map { it.name })

        val ascending = CompetitorSorter.sorted(
            items, CompetitorSortOrder.ScoreAscending, { it.name }, { it.tally })
        // Fewest wins first (no-games has zero wins → first), then lower win%.
        // The name tie-break stays A–Z regardless of direction: Cara before Dan.
        assertEquals(listOf("Eve", "Cara", "Dan", "Bea", "Abe"), ascending.map { it.name })
    }

    @Test
    fun ranksUnplayedBelowAnAllLossRecord() {
        val items = listOf(
            SortableCompetitor(name = "Ghost", tally = Tally()),                    // never played → null %
            SortableCompetitor(name = "Loser", tally = Tally(played = 3, won = 0)), // played, 0%
        )

        val descending = CompetitorSorter.sorted(
            items, CompetitorSortOrder.ScoreDescending, { it.name }, { it.tally })
        // Both have zero wins, but a real 0% record outranks "no games yet".
        assertEquals(listOf("Loser", "Ghost"), descending.map { it.name })
    }
}
