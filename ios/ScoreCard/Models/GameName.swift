//
//  GameName.swift
//  ScoreCard
//
//  A reusable game-name template (e.g. "Scopa", "Briscola") the user can pick
//  from when starting a new game. Editable independently of the games that use
//  it — deleting a name never touches past games (there is no relationship; a
//  Game just copies the chosen name into its own `title`). `lastUsedAt` records
//  when a game was last started with this name, so New Game can pre-select the
//  most recently used one.
//

import Foundation
import SwiftData

@Model
final class GameName {
    // CloudKit requires every stored property to be optional or have a default.
    var name: String = ""
    var createdAt: Date = Date.now
    /// When a game was last started with this name. `.distantPast` until first
    /// used, so freshly added names sort after ones that have actually been used.
    var lastUsedAt: Date = Date.distantPast

    init(name: String, createdAt: Date = .now, lastUsedAt: Date = .distantPast) {
        self.name = name
        self.createdAt = createdAt
        self.lastUsedAt = lastUsedAt
    }

    /// One-time backfill so users upgrading with existing games immediately see a
    /// useful list: create a `GameName` for each distinct game title (matched
    /// case-insensitively), stamping `lastUsedAt` from the most recent game that
    /// used it. No-op when names already exist or there are no games. The caller
    /// guards repeat runs with a flag; the in-method check is a second safety net.
    static func seedFromExistingGames(context: ModelContext) {
        let existing = (try? context.fetch(FetchDescriptor<GameName>())) ?? []
        guard existing.isEmpty else { return }

        let games = (try? context.fetch(FetchDescriptor<Game>())) ?? []
        guard !games.isEmpty else { return }

        // Per case-insensitive title, keep the spelling and creation date of the
        // most recently created game that used it (so the latest spelling wins
        // and `lastUsedAt` reflects the newest game). Independent of fetch order.
        var byKey: [String: (name: String, lastUsedAt: Date)] = [:]
        for game in games {
            let title = game.title.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty else { continue }
            let key = title.localizedLowercase
            if let current = byKey[key], current.lastUsedAt >= game.createdAt { continue }
            byKey[key] = (title, game.createdAt)
        }

        for entry in byKey.values {
            context.insert(GameName(name: entry.name, lastUsedAt: entry.lastUsedAt))
        }
        try? context.save()
    }
}
