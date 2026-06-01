//
//  ScoreCardSchema.swift
//  ScoreCard
//
//  Single source of truth for the list of persisted model types, shared by the
//  live CloudKit-backed container and the in-memory preview container.
//

import Foundation
import SwiftData

enum ScoreCardSchema {
    /// All `@Model` types in the app, in dependency-friendly order.
    static let models: [any PersistentModel.Type] = [
        Player.self,
        Team.self,
        Game.self,
        GameParticipant.self,
        ScoreEntry.self,
        Seat.self,
    ]
}
