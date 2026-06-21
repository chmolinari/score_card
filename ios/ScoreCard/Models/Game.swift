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

    // Seating around the table, ordered counter-clockwise from the first dealer.
    // Holds individual people even when the competitors are teams.
    @Relationship(deleteRule: .cascade, inverse: \Seat.game)
    var seats: [Seat]? = []

    // Which seat is dealing the current hand (manche). Index into `orderedSeats`.
    var currentDealerIndex: Int = 0

    // Which hand (manche) is being played, starting at 1 for the opening hand and
    // incremented each time the deal passes to the next player.
    var currentHand: Int = 1

    init(title: String, hasTarget: Bool = false, targetPoints: Int? = nil) {
        self.title = title
        self.hasTarget = hasTarget
        self.targetPoints = hasTarget ? targetPoints : nil
        self.createdAt = .now
    }

    /// A game is "open" (still being scored) until it is explicitly closed.
    var isOpen: Bool { closedAt == nil }

    /// Participants paired with their total score, ranked highest-first.
    ///
    /// Each participant's total is summed from its score entries exactly once
    /// here, so callers that need both the ranking and the scores (the live
    /// scoreboard) don't re-walk every entry multiple times per render.
    var rankedScores: [(participant: GameParticipant, score: Int)] {
        (participants ?? [])
            .map { (participant: $0, score: $0.totalScore) }
            .sorted { a, b in
                if a.score != b.score { return a.score > b.score }
                return a.participant.sortIndex < b.participant.sortIndex
            }
    }

    /// Participants unwrapped, ranked by score (highest first) for the scoreboard.
    var rankedParticipants: [GameParticipant] {
        rankedScores.map(\.participant)
    }

    /// The participant currently in the lead, if the game has any.
    var leader: GameParticipant? { rankedScores.first?.participant }

    /// Competitors that share the top score. While the game is open this is the
    /// current front-runner(s); once closed it's the final winner(s) — more than
    /// one means the game ended in a draw.
    var topScorers: [GameParticipant] {
        let ranked = rankedScores
        guard let best = ranked.first?.score else { return [] }
        return ranked.filter { $0.score == best }.map(\.participant)
    }

    /// A closed game is a draw when no single competitor has the top score.
    var isDraw: Bool { !isOpen && topScorers.count > 1 }

    /// The participant(s) that have reached the target, if one is set.
    var winnersAtTarget: [GameParticipant] {
        guard hasTarget, let target = targetPoints else { return [] }
        return rankedScores.filter { $0.score >= target }.map(\.participant)
    }

    /// Competitors in a fixed table order for the live scoreboard, so the rows
    /// don't shuffle by score during play.
    ///
    /// The first dealer (the one drawn at seat position 0) — or the team that
    /// player belongs to — is on top; everyone else follows the dealing
    /// rotation in the given direction. A team is placed by its earliest-dealing
    /// member ("the first of its players to deal next"). When no seating has been
    /// set up yet, falls back to the order the participants were added.
    func participantsInDealingOrder(_ direction: DealingDirection) -> [GameParticipant] {
        let parts = participants ?? []
        let seats = orderedSeats
        guard !seats.isEmpty else {
            return parts.sorted { $0.sortIndex < $1.sortIndex }
        }

        // Dealing rank of each seated player: 0 for the first dealer, then in the
        // order the deal passes. Seats are stored counter-clockwise from
        // position 0, so counter-clockwise dealing keeps that order and clockwise
        // dealing reverses everyone after the first dealer.
        let count = seats.count
        var rankOf: [PersistentIdentifier: Int] = [:]
        for seat in seats {
            guard let player = seat.player else { continue }
            rankOf[player.persistentModelID] = ((seat.position * direction.step) % count + count) % count
        }

        func dealingRank(_ participant: GameParticipant) -> Int {
            if let player = participant.player {
                return rankOf[player.persistentModelID] ?? Int.max
            }
            if let team = participant.team {
                return (team.members ?? []).compactMap { rankOf[$0.persistentModelID] }.min() ?? Int.max
            }
            return Int.max
        }

        return parts.sorted { a, b in
            let ra = dealingRank(a), rb = dealingRank(b)
            if ra != rb { return ra < rb }
            return a.sortIndex < b.sortIndex
        }
    }

    /// Seats ordered counter-clockwise from the first dealer (position 0).
    var orderedSeats: [Seat] {
        (seats ?? []).sorted { $0.position < $1.position }
    }

    /// Whether a seating order / dealer has been set for this game.
    var hasSeating: Bool { !(seats ?? []).isEmpty }

    /// The player dealing the current hand, if seating is set.
    var currentDealer: Player? { dealer(atOffset: 0) }

    /// The player who deals the next hand, given the dealing direction.
    func nextDealer(_ direction: DealingDirection) -> Player? {
        dealer(atOffset: direction.step)
    }

    /// Move the deal to the next player in the given direction (new hand).
    func advanceDealer(_ direction: DealingDirection) {
        let count = orderedSeats.count
        guard count > 0 else { return }
        currentDealerIndex = ((currentDealerIndex + direction.step) % count + count) % count
    }

    /// Player seated `offset` steps from the current dealer (wrapping).
    private func dealer(atOffset offset: Int) -> Player? {
        let seats = orderedSeats
        guard !seats.isEmpty else { return nil }
        let count = seats.count
        let index = ((currentDealerIndex + offset) % count + count) % count
        return seats[index].player
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
