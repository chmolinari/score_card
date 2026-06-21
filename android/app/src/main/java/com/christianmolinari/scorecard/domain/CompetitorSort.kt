package com.christianmolinari.scorecard.domain

// Numeric-aware, case-insensitive comparator approximating iOS
// localizedStandardCompare (Finder-style ordering): runs of digits compare as
// numbers, so "Player 2" sorts before "Player 10". When one string is a prefix
// of the other, the one with the longer remaining suffix sorts after.
object NameComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // Compare whole digit runs numerically. Strip leading zeros and
                // compare by length-then-lexicographically so arbitrarily long
                // runs can't overflow an Int.
                val startA = i
                while (i < a.length && a[i].isDigit()) i++
                val startB = j
                while (j < b.length && b[j].isDigit()) j++
                val runA = a.substring(startA, i).trimStart('0')
                val runB = b.substring(startB, j).trimStart('0')
                val cmp = if (runA.length != runB.length) runA.length - runB.length else runA.compareTo(runB)
                if (cmp != 0) return cmp
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                i++
                j++
            }
        }
        // Both consumed equally so far: the longer remaining suffix sorts after.
        return (a.length - i) - (b.length - j)
    }
}

// A field (name or score) plus a direction the roster lists can be ordered by.
// "Score" means games won. Raw values are stable preference strings shared with
// the iOS app — renaming them would silently reset everyone's saved preference,
// so don't.
enum class CompetitorSortOrder(val rawValue: String, val label: String) {
    NameAscending("nameAscending", "Name (A–Z)"),
    NameDescending("nameDescending", "Name (Z–A)"),
    ScoreDescending("scoreDescending", "Wins (high to low)"),
    ScoreAscending("scoreAscending", "Wins (low to high)");

    // Direction arrow shown beside each option in the sort menu.
    val isAscendingArrow: Boolean
        get() = this == NameAscending || this == ScoreAscending

    companion object {
        fun fromRaw(raw: String?): CompetitorSortOrder =
            entries.firstOrNull { it.rawValue == raw } ?: NameAscending
    }
}

// Orders competitors (players or teams) for the roster lists. Pure and
// persistence-free — it takes plain lambdas — so it can be unit-tested with
// lightweight values, mirroring FrequentPicker.
object CompetitorSorter {
    // Returns `items` ordered per `order`.
    //
    // "Score" ranks by games won; ties are broken by win percentage and then by
    // name. Name sorts use a numeric-aware comparison ("Player 2" before
    // "Player 10"). Equal-ranked items always fall back to alphabetical (A–Z)
    // order, regardless of direction, so the result is stable.
    fun <T> sorted(
        items: List<T>,
        order: CompetitorSortOrder,
        name: (T) -> String,
        tally: (T) -> Tally,
    ): List<T> = when (order) {
        // `name` is a cheap stored-property read, so comparing in place is fine.
        CompetitorSortOrder.NameAscending ->
            items.sortedWith(compareBy(NameComparator) { name(it) })
        CompetitorSortOrder.NameDescending ->
            items.sortedWith(compareByDescending(NameComparator) { name(it) })
        CompetitorSortOrder.ScoreDescending, CompetitorSortOrder.ScoreAscending -> {
            // The tally walks every game participation, and sorting would call it
            // O(n log n) times, so decorate each item with its tally once up
            // front, sort the decorations, then undecorate.
            val descending = order == CompetitorSortOrder.ScoreDescending
            items
                .map { Scored(item = it, name = name(it), tally = tally(it)) }
                .sortedWith(scoreComparator(descending))
                .map { it.item }
        }
    }

    // An item paired with its already-computed sort keys.
    private class Scored<T>(val item: T, val name: String, val tally: Tally)

    // Strict ordering by score: wins first, then win percentage (a competitor
    // with no finished games — null percentage — ranks below an all-losses 0%),
    // then name A–Z as a stable, direction-independent final tie-break.
    private fun <T> scoreComparator(descending: Boolean): Comparator<Scored<T>> {
        val wins = compareBy<Scored<T>> { it.tally.won }
        val percentage = compareBy<Scored<T>> { it.tally.winPercentage ?: -1 }
        val direction = if (descending) wins.reversed().then(percentage.reversed())
        else wins.then(percentage)
        return direction.then(compareBy(NameComparator) { it.name })
    }
}
