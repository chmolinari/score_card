//
//  Tally.swift
//  ScoreCard
//
//  A small value type summarising a player's or team's record: how many
//  finished games they played, how many they won, and how many are in progress.
//  Computed on the fly from their game participations — nothing extra is stored.
//

import Foundation

struct Tally: Equatable {
    /// Number of finished (closed) games this competitor took part in.
    var played: Int = 0
    /// Finished games won. A tie for first counts as a win for everyone tied.
    var won: Int = 0
    /// Games currently still open.
    var inProgress: Int = 0

    /// Whole-number win percentage over finished games, or nil if none played.
    var winPercentage: Int? {
        guard played > 0 else { return nil }
        return Int((Double(won) / Double(played) * 100).rounded())
    }

    /// True when there is nothing to show yet.
    var isEmpty: Bool { played == 0 && inProgress == 0 }

    /// Builds a tally from a set of game participations (a player's or team's).
    static func from(_ participations: [GameParticipant]) -> Tally {
        var tally = Tally()
        for participation in participations {
            guard let game = participation.game else { continue }
            if game.isOpen {
                tally.inProgress += 1
            } else {
                tally.played += 1
                if participation.isWinner { tally.won += 1 }
            }
        }
        return tally
    }
}
