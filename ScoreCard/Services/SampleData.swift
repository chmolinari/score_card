//
//  SampleData.swift
//  ScoreCard
//
//  Builds an in-memory ModelContainer pre-populated with a few players, teams,
//  and games. Used by SwiftUI #Preview blocks so screens render with content
//  without ever touching real (or CloudKit-backed) storage.
//

import Foundation
import SwiftData

@MainActor
enum SampleData {
    /// A throwaway in-memory container seeded with representative data.
    static let container: ModelContainer = {
        let schema = Schema(ScoreCardSchema.models)
        // Explicitly disable CloudKit for the throwaway preview store.
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
        let container: ModelContainer
        do {
            container = try ModelContainer(for: schema, configurations: [config])
        } catch {
            fatalError("Failed to build sample ModelContainer: \(error)")
        }

        let context = container.mainContext

        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        let carol = Player(name: "Carol")
        let dave = Player(name: "Dave")
        [alice, bob, carol, dave].forEach(context.insert)

        let redTeam = Team(name: "The Aces", members: [alice, bob])
        let blueTeam = Team(name: "Wild Cards", members: [carol, dave])
        [redTeam, blueTeam].forEach(context.insert)

        // An open game with a target (e.g. Scopa to 11).
        let scopa = Game(title: "Scopa", hasTarget: true, targetPoints: 11)
        scopa.locationName = "Napoli, Italy"
        scopa.latitude = 40.8518
        scopa.longitude = 14.2681
        context.insert(scopa)
        let scopaA = GameParticipant(team: redTeam, sortIndex: 0)
        let scopaB = GameParticipant(team: blueTeam, sortIndex: 1)
        scopaA.game = scopa
        scopaB.game = scopa
        context.insert(scopaA)
        context.insert(scopaB)
        addEntry(7, to: scopaA, in: context)
        addEntry(4, to: scopaB, in: context)

        // A finished, open-ended game (e.g. Briscola).
        let briscola = Game(title: "Briscola", hasTarget: false)
        briscola.closedAt = .now
        briscola.locationName = "Roma, Italy"
        context.insert(briscola)
        let briscolaA = GameParticipant(player: alice, sortIndex: 0)
        let briscolaB = GameParticipant(player: bob, sortIndex: 1)
        briscolaA.game = briscola
        briscolaB.game = briscola
        context.insert(briscolaA)
        context.insert(briscolaB)
        addEntry(120, to: briscolaA, in: context)
        addEntry(100, to: briscolaB, in: context)

        try? context.save()
        return container
    }()

    private static func addEntry(_ points: Int, to participant: GameParticipant, in context: ModelContext) {
        let entry = ScoreEntry(points: points)
        entry.participant = participant
        context.insert(entry)
    }
}
