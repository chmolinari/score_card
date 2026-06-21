//
//  GameNamePicker.swift
//  ScoreCard
//
//  Pure (SwiftData-free) helper for choosing which game name New Game should
//  pre-select: the most recently used, falling back to alphabetical order so
//  the choice is deterministic. Kept free of SwiftData so it is trivially
//  unit-testable, like FrequentPicker.
//

import Foundation

enum GameNamePicker {
    /// The item to pre-select by default: the most recently used one. Ties (and
    /// never-used items, which all share `.distantPast`) are broken alphabetically
    /// so the result is stable. Returns nil for an empty list.
    static func defaultSelection<T>(_ items: [T],
                                    lastUsed: (T) -> Date,
                                    name: (T) -> String) -> T? {
        items
            .sorted { a, b in
                let la = lastUsed(a), lb = lastUsed(b)
                if la != lb { return la > lb }   // most recent first
                return name(a).localizedStandardCompare(name(b)) == .orderedAscending
            }
            .first
    }
}
