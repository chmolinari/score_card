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

    /// The date a registered game is filed under, given how much the user
    /// remembers: date and time are taken verbatim; a date without a time files
    /// the game at the exact start of that day (a real stamp essentially never
    /// lands on 00:00:00 sharp, so display treats midnight as "date only");
    /// no date at all files it under the registration moment, like a live game.
    static func playedDate(hasDate: Bool, hasTime: Bool, selection: Date,
                           now: Date = .now, calendar: Calendar = .current) -> Date {
        guard hasDate else { return now }
        guard hasTime else { return calendar.startOfDay(for: selection) }
        return selection
    }

    /// Create and save a closed, backdated game. `createdAt` and `closedAt` are
    /// both the played-on date, so history ordering follows when the game was
    /// actually played. A transcribed final total obeys the below-zero
    /// preference exactly like a played one: with it off, a negative final
    /// lands on zero rather than slipping past the user's choice.
    @discardableResult
    static func register(title: String,
                         finalScores: [FinalScore],
                         playedAt: Date,
                         locationName: String?,
                         // No default: a transcribed total must state which
                         // policy it was written under, so dropping the argument
                         // is a compile error rather than a silent revert to
                         // clamping.
                         allowNegativeScores: Bool,
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

            // A transcribed total obeys the same below-zero policy as a played
            // one, so a past game can't be a back door past the user's choice.
            let entry = ScoreEntry(points: NegativeScores.clamped(finalScore.points,
                                                                  allowNegative: allowNegativeScores),
                                   timestamp: playedAt)
            entry.participant = participant
            context.insert(entry)
        }

        try context.save()
        return game
    }
}
