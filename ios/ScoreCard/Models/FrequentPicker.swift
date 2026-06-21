//
//  FrequentPicker.swift
//  ScoreCard
//
//  Ranks items by how often they've been used, to surface the most-used
//  players and teams at the top of the New Game selectors. Kept free of
//  SwiftData so it is trivially unit-testable.
//

import Foundation

enum FrequentPicker {
    /// Default number of "most used" entries to surface.
    static let defaultLimit = 5

    /// Returns up to `limit` items with the highest usage, most-used first.
    /// Items with zero usage are excluded (they aren't "used" yet). Ties are
    /// broken by name so the order is stable.
    static func top<T>(_ items: [T],
                       limit: Int = defaultLimit,
                       usage: (T) -> Int,
                       name: (T) -> String) -> [T] {
        items
            .filter { usage($0) > 0 }
            .sorted { a, b in
                let ua = usage(a), ub = usage(b)
                if ua != ub { return ua > ub }
                return name(a).localizedStandardCompare(name(b)) == .orderedAscending
            }
            .prefix(limit)
            .map { $0 }
    }
}
