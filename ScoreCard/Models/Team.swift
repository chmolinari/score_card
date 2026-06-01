//
//  Team.swift
//  ScoreCard
//
//  A named group of players that competes as a single unit.
//

import Foundation
import SwiftData

@Model
final class Team {
    var name: String = ""
    var createdAt: Date = Date.now

    // Many-to-many with Player. Inverse is declared on Player.teams.
    var members: [Player]? = []

    // Game appearances for this team. Nullified when the team is deleted.
    @Relationship(inverse: \GameParticipant.team)
    var participations: [GameParticipant]? = []

    init(name: String, members: [Player] = []) {
        self.name = name
        self.createdAt = .now
        self.members = members
    }

    /// Members unwrapped and sorted by name for display.
    var sortedMembers: [Player] {
        (members ?? []).sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    /// Win/play record across the games this team took part in.
    var tally: Tally {
        Tally.from(participations ?? [])
    }

    /// How many games this team has appeared in (open or finished). Used to
    /// surface frequently used teams in the New Game selector.
    var usageCount: Int { (participations ?? []).count }

    /// "Alice, Bob & Carol" style summary of the roster.
    var rosterSummary: String {
        let names = sortedMembers.map(\.name)
        switch names.count {
        case 0: return "No members"
        case 1: return names[0]
        default:
            let head = names.dropLast().joined(separator: ", ")
            return "\(head) & \(names.last!)"
        }
    }
}
