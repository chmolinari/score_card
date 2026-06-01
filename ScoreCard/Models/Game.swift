//
//  Game.swift
//  ScoreCard
//
//  A single match. Holds its competitors (players and/or teams as GameParticipants),
//  an optional target score, and the date/time/location it was created.
//

import Foundation
import CoreLocation
import SwiftData

@Model
final class Game {
    var title: String = ""

    // Some games race to a target (e.g. Scopa to 11/21); others are open-ended
    // and just track running totals (e.g. Briscola). `targetPoints` is only
    // meaningful when `hasTarget` is true.
    var hasTarget: Bool = false
    var targetPoints: Int?

    // Requirement: every game is tagged with date + time. This is the creation
    // (kick-off) timestamp. `closedAt` is nil while the game is in progress.
    var createdAt: Date = Date.now
    var closedAt: Date?

    // Requirement: every game is tagged with geolocation. Stored as primitive
    // components (CloudKit has no CLLocation type). All optional because the user
    // may decline location permission.
    var latitude: Double?
    var longitude: Double?
    var locationName: String?

    @Relationship(deleteRule: .cascade, inverse: \GameParticipant.game)
    var participants: [GameParticipant]? = []

    init(title: String, hasTarget: Bool = false, targetPoints: Int? = nil) {
        self.title = title
        self.hasTarget = hasTarget
        self.targetPoints = hasTarget ? targetPoints : nil
        self.createdAt = .now
    }

    /// A game is "open" (still being scored) until it is explicitly closed.
    var isOpen: Bool { closedAt == nil }

    /// Participants unwrapped, ranked by score (highest first) for the scoreboard.
    var rankedParticipants: [GameParticipant] {
        (participants ?? []).sorted { a, b in
            if a.totalScore != b.totalScore { return a.totalScore > b.totalScore }
            return a.sortIndex < b.sortIndex
        }
    }

    /// The participant currently in the lead, if the game has any.
    var leader: GameParticipant? { rankedParticipants.first }

    /// The participant(s) that have reached the target, if one is set.
    var winnersAtTarget: [GameParticipant] {
        guard hasTarget, let target = targetPoints else { return [] }
        return rankedParticipants.filter { $0.totalScore >= target }
    }

    /// CoreLocation coordinate reconstructed from the stored components.
    var coordinate: CLLocationCoordinate2D? {
        guard let latitude, let longitude else { return nil }
        return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    /// Apply a captured location to the game.
    func apply(location: CapturedLocation?) {
        latitude = location?.latitude
        longitude = location?.longitude
        locationName = location?.placeName
    }
}
