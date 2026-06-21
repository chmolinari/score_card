package com.christianmolinari.scorecard.domain

import java.time.Instant

// Ranks items by how often they've been used, to surface the most-used
// players and teams at the top of the New Game selectors. Kept free of the
// persistence layer so it is trivially unit-testable.
object FrequentPicker {
    // Default number of "most used" entries to surface.
    const val DEFAULT_LIMIT = 5

    // Returns up to `limit` items with the highest usage, most-used first.
    // Items with zero usage are excluded (they aren't "used" yet). Ties are
    // broken by name so the order is stable.
    fun <T> top(
        items: List<T>,
        limit: Int = DEFAULT_LIMIT,
        usage: (T) -> Int,
        name: (T) -> String,
    ): List<T> =
        items
            .filter { usage(it) > 0 }
            .sortedWith(
                compareByDescending<T> { usage(it) }
                    .then(compareBy(NameComparator) { name(it) })
            )
            .take(limit)
}

// Pure (persistence-free) helper for choosing which game name New Game should
// pre-select: the most recently used, falling back to alphabetical order so
// the choice is deterministic. Kept free of the persistence layer so it is
// trivially unit-testable, like FrequentPicker.
object GameNamePicker {
    // The item to pre-select by default: the most recently used one. Ties (and
    // never-used items, which all share DISTANT_PAST) are broken alphabetically
    // so the result is stable. Returns null for an empty list.
    fun <T> defaultSelection(
        items: List<T>,
        lastUsed: (T) -> Instant,
        name: (T) -> String,
    ): T? =
        items.minWithOrNull(
            compareByDescending<T> { lastUsed(it) }   // most recent first
                .then(compareBy(NameComparator) { name(it) })
        )
}
