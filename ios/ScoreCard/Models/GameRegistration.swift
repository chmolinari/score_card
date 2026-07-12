//
//  GameRegistration.swift
//  ScoreCard
//
//  Persists a game that was played outside the app (before the app existed, or
//  scored on paper): an already-closed game backdated to the played-on date,
//  with one score entry per competitor carrying their final total. No seats,
//  target, or geotag — only what a transcription can know.
//

import Foundation
import SwiftData

@MainActor
enum GameRegistration {

    /// A competitor together with their final total in the finished game.
    struct FinalScore {
        let competitor: GameCompetitor
        let points: Int
    }

    /// Create and save a closed, backdated game. `createdAt` and `closedAt` are
    /// both the played-on date, so history ordering follows when the game was
    /// actually played. Final totals may be zero or negative — the below-zero
    /// scoring preference only governs live subtraction, not transcribed results.
    @discardableResult
    static func register(title: String,
                         finalScores: [FinalScore],
                         playedAt: Date,
                         locationName: String?,
                         in context: ModelContext) throws -> Game {
        let game = Game(title: title)
        game.createdAt = playedAt
        game.closedAt = playedAt

        let trimmedLocation = locationName?.trimmingCharacters(in: .whitespacesAndNewlines)
        game.locationName = (trimmedLocation?.isEmpty ?? true) ? nil : trimmedLocation
        context.insert(game)

        for (index, finalScore) in finalScores.enumerated() {
            let participant: GameParticipant
            switch finalScore.competitor {
            case .player(let player):
                participant = GameParticipant(player: player, sortIndex: index)
            case .team(let team):
                participant = GameParticipant(team: team, sortIndex: index)
            }
            participant.game = game
            context.insert(participant)

            let entry = ScoreEntry(points: finalScore.points, timestamp: playedAt)
            entry.participant = participant
            context.insert(entry)
        }

        try context.save()
        return game
    }
}
