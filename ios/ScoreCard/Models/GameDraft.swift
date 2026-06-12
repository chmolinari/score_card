//
//  GameDraft.swift
//  ScoreCard
//
//  In-flight description of a game being created, carried from the New Game
//  screen to the seating step before anything is persisted.
//

import Foundation
import SwiftData

/// A competitor chosen for a game: either a single player or a team. Wraps the
/// model object directly so a selection survives the object's persistent-ID
/// changing on first save.
enum GameCompetitor: Identifiable, Equatable {
    case player(Player)
    case team(Team)

    var id: PersistentIdentifier {
        switch self {
        case .player(let player): return player.persistentModelID
        case .team(let team): return team.persistentModelID
        }
    }

    var name: String {
        switch self {
        case .player(let player): return player.name
        case .team(let team): return team.name
        }
    }

    static func == (lhs: GameCompetitor, rhs: GameCompetitor) -> Bool {
        switch (lhs, rhs) {
        case let (.player(a), .player(b)): return a === b
        case let (.team(a), .team(b)): return a === b
        default: return false
        }
    }
}

struct GameDraft: Identifiable, Hashable {
    let id = UUID()
    var title: String
    var hasTarget: Bool
    var targetPoints: Int?
    var competitors: [GameCompetitor]

    /// All distinct individual people involved, in competitor order, with teams
    /// expanded to their members. These are the people who can deal.
    var people: [Player] {
        var seen = Set<PersistentIdentifier>()
        var result: [Player] = []
        func add(_ player: Player) {
            if seen.insert(player.persistentModelID).inserted { result.append(player) }
        }
        for competitor in competitors {
            switch competitor {
            case .player(let player): add(player)
            case .team(let team): team.sortedMembers.forEach(add)
            }
        }
        return result
    }

    static func == (lhs: GameDraft, rhs: GameDraft) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}
