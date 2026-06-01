//
//  Player.swift
//  ScoreCard
//
//  A single person who can play games, alone or as a member of one or more teams.
//

import Foundation
import SwiftData

@Model
final class Player {
    // CloudKit requires every stored property to be optional or have a default value.
    var name: String = ""
    var createdAt: Date = Date.now

    // Many-to-many with Team. The inverse is declared here; Team.members has no
    // explicit inverse so SwiftData links the two automatically.
    @Relationship(inverse: \Team.members)
    var teams: [Team]? = []

    // Every game appearance that points at this player. Nullified (not cascaded)
    // when the player is deleted, so historical games keep their snapshot name.
    @Relationship(inverse: \GameParticipant.player)
    var participations: [GameParticipant]? = []

    // Table seats this player occupies across games (inverse of Seat.player).
    @Relationship(inverse: \Seat.player)
    var seatings: [Seat]? = []

    init(name: String) {
        self.name = name
        self.createdAt = .now
    }

    /// Teams this player belongs to, unwrapped and sorted for display.
    var sortedTeams: [Team] {
        (teams ?? []).sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    /// Win/play record across the games this player took part in as an individual.
    var tally: Tally {
        Tally.from(participations ?? [])
    }

    /// How many games this player has appeared in (open or finished). Used to
    /// surface frequently used players in the New Game selector.
    var usageCount: Int { (participations ?? []).count }
}
