//
//  SampleDataSeeder.swift
//  ScoreCard
//
//  One-time seeding of realistic players, teams, and games into the live store,
//  for testing. Triggered by launching with the "-seedSampleData" argument and
//  guarded by a UserDefaults flag so it runs at most once per install.
//

import Foundation
import SwiftData

@MainActor
enum SampleDataSeeder {
    private static let seededKey = "ScoreCardDidSeedSampleData_v1"

    /// Seeds the store once. Safe to call on every launch — it no-ops after the
    /// first successful run.
    static func seedIfNeeded(into context: ModelContext) {
        guard !UserDefaults.standard.bool(forKey: seededKey) else { return }
        seed(into: context)
        UserDefaults.standard.set(true, forKey: seededKey)
    }

    static func seed(into context: ModelContext) {
        // Players.
        let names = ["Marco", "Giulia", "Luca", "Sofia", "Alessandro", "Chiara", "Matteo", "Francesca"]
        var players: [String: Player] = [:]
        for name in names {
            let player = Player(name: name)
            context.insert(player)
            players[name] = player
        }
        func p(_ name: String) -> Player { players[name]! }

        // Teams.
        func makeTeam(_ name: String, _ members: [String]) -> Team {
            let team = Team(name: name, members: members.map(p))
            context.insert(team)
            return team
        }
        let campioni = makeTeam("I Campioni", ["Marco", "Giulia"])
        let assi = makeTeam("Assi di Briscola", ["Luca", "Sofia"])
        let masters = makeTeam("Scopa Masters", ["Alessandro", "Chiara"])
        let settebello = makeTeam("Settebello", ["Matteo", "Francesca"])

        let day = 86_400.0

        // Helper to assemble a game with its competitors and final scores.
        func makeGame(_ title: String,
                      target: Int? = nil,
                      daysAgo: Double,
                      location: (name: String, lat: Double, lon: Double)? = nil,
                      closed: Bool = true,
                      players playerScores: [(Player, Int)] = [],
                      teams teamScores: [(Team, Int)] = []) {
            let game = Game(title: title, hasTarget: target != nil, targetPoints: target)
            let created = Date.now.addingTimeInterval(-daysAgo * day)
            game.createdAt = created
            if let location {
                game.latitude = location.lat
                game.longitude = location.lon
                game.locationName = location.name
            }
            context.insert(game)

            var index = 0
            for (player, score) in playerScores {
                addParticipant(GameParticipant(player: player, sortIndex: index), score: score, to: game, in: context)
                index += 1
            }
            for (team, score) in teamScores {
                addParticipant(GameParticipant(team: team, sortIndex: index), score: score, to: game, in: context)
                index += 1
            }

            if closed {
                game.closedAt = created.addingTimeInterval(Double.random(in: 20...75) * 60)
            }
        }

        // Cities used to geo-tag a few games.
        let napoli = (name: "Napoli, Italy", lat: 40.8518, lon: 14.2681)
        let roma = (name: "Roma, Italy", lat: 41.9028, lon: 12.4964)
        let milano = (name: "Milano, Italy", lat: 45.4642, lon: 9.1900)
        let firenze = (name: "Firenze, Italy", lat: 43.7696, lon: 11.2558)

        // Finished games — a mix of team vs team and player vs player, Scopa
        // (target) and Briscola/Tressette (open-ended), spread across dates.
        makeGame("Scopa", target: 11, daysAgo: 1, location: napoli, teams: [(campioni, 11), (assi, 7)])
        makeGame("Scopa", target: 11, daysAgo: 3, location: roma, teams: [(campioni, 11), (masters, 9)])
        makeGame("Briscola", daysAgo: 5, location: milano, players: [(p("Marco"), 120), (p("Luca"), 90)])
        makeGame("Briscola", daysAgo: 6, players: [(p("Marco"), 95), (p("Giulia"), 110)])
        makeGame("Scopa", target: 21, daysAgo: 8, location: firenze, teams: [(campioni, 21), (settebello, 15)])
        makeGame("Scopa", target: 11, daysAgo: 10, teams: [(assi, 11), (masters, 8)])
        makeGame("Briscola", daysAgo: 12, players: [(p("Sofia"), 100), (p("Chiara"), 80)])
        makeGame("Scopa", target: 11, daysAgo: 14, teams: [(campioni, 6), (assi, 11)])
        makeGame("Tressette", target: 31, daysAgo: 15, players: [(p("Marco"), 31), (p("Alessandro"), 25)])
        makeGame("Scopa", target: 11, daysAgo: 20, players: [(p("Giulia"), 11), (p("Matteo"), 9)])

        // Two games still in progress.
        makeGame("Scopa", target: 11, daysAgo: 0.02, location: roma, closed: false,
                 teams: [(campioni, 4), (masters, 6)])
        makeGame("Briscola", daysAgo: 0.01, closed: false,
                 players: [(p("Marco"), 30), (p("Sofia"), 25)])

        try? context.save()
    }

    private static func addParticipant(_ participant: GameParticipant, score: Int, to game: Game, in context: ModelContext) {
        participant.game = game
        context.insert(participant)
        if score != 0 {
            let entry = ScoreEntry(points: score)
            entry.participant = participant
            context.insert(entry)
        }
    }
}
