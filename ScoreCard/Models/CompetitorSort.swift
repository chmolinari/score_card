//
//  CompetitorSort.swift
//  ScoreCard
//
//  How the Players and Teams lists are ordered. The chosen order is an app-wide
//  preference (UserDefaults via @AppStorage), kept per tab. The sort itself is
//  free of SwiftData so it is trivially unit-testable, like FrequentPicker.
//

import Foundation

/// A field (name or score) plus a direction the roster lists can be ordered by.
/// "Score" means games won. Raw values are stable UserDefaults strings — renaming
/// them would silently reset everyone's saved preference, so don't.
enum CompetitorSortOrder: String, CaseIterable, Identifiable {
    case nameAscending
    case nameDescending
    case scoreDescending
    case scoreAscending

    var id: String { rawValue }

    /// Title-case label for the sort menu.
    var label: String {
        switch self {
        case .nameAscending: return "Name (A–Z)"
        case .nameDescending: return "Name (Z–A)"
        case .scoreDescending: return "Wins (high to low)"
        case .scoreAscending: return "Wins (low to high)"
        }
    }

    /// Direction arrow shown beside each option.
    var systemImage: String {
        switch self {
        case .nameAscending, .scoreAscending: return "arrow.up"
        case .nameDescending, .scoreDescending: return "arrow.down"
        }
    }

    /// UserDefaults key for the Players tab preference.
    static let playersStorageKey = "playersSortOrder"
    /// UserDefaults key for the Teams tab preference.
    static let teamsStorageKey = "teamsSortOrder"
}

/// Orders competitors (players or teams) for the roster lists. Pure and
/// SwiftData-free — it takes plain closures — so it can be unit-tested with
/// lightweight values, mirroring `FrequentPicker`.
enum CompetitorSorter {
    /// Returns `items` ordered per `order`.
    ///
    /// "Score" ranks by games won; ties are broken by win percentage and then by
    /// name. Name sorts use a localized, numeric-aware comparison ("Player 2"
    /// before "Player 10"). Equal-ranked items always fall back to alphabetical
    /// (A–Z) order, regardless of direction, so the result is stable.
    static func sorted<T>(_ items: [T],
                          by order: CompetitorSortOrder,
                          name: (T) -> String,
                          tally: (T) -> Tally) -> [T] {
        switch order {
        case .nameAscending:
            // `name` is a cheap stored-property read, so comparing in place is fine.
            return items.sorted { name($0).localizedStandardCompare(name($1)) == .orderedAscending }
        case .nameDescending:
            return items.sorted { name($0).localizedStandardCompare(name($1)) == .orderedDescending }
        case .scoreDescending, .scoreAscending:
            // The tally faults a SwiftData relationship, and `sorted` would call it
            // O(n log n) times, so decorate each item with its tally once up front,
            // sort the decorations, then undecorate.
            let descending = order == .scoreDescending
            return items
                .map { Scored(item: $0, name: name($0), tally: tally($0)) }
                .sorted { ordersByScore($0, before: $1, descending: descending) }
                .map(\.item)
        }
    }

    /// An item paired with its already-computed sort keys.
    private struct Scored<T> {
        let item: T
        let name: String
        let tally: Tally
    }

    /// Strict ordering by score: wins first, then win percentage (a competitor
    /// with no finished games — `nil` percentage — ranks below an all-losses 0%),
    /// then name A–Z as a stable, direction-independent final tie-break.
    private static func ordersByScore<T>(_ a: Scored<T>, before b: Scored<T>,
                                         descending: Bool) -> Bool {
        if a.tally.won != b.tally.won {
            return descending ? a.tally.won > b.tally.won : a.tally.won < b.tally.won
        }
        let pa = a.tally.winPercentage ?? -1
        let pb = b.tally.winPercentage ?? -1
        if pa != pb {
            return descending ? pa > pb : pa < pb
        }
        return a.name.localizedStandardCompare(b.name) == .orderedAscending
    }
}
