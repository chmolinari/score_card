//
//  ActionLogRecorderTests.swift
//  ScoreCardTests
//
//  Proves the recorder actually captures store mutations, and captures the one
//  detail the whole feature exists for: when a player is deleted, the log must
//  still name the teams they belonged to. That is precisely what nobody could
//  establish after two players disappeared in July 2026 — the backups showed
//  the loss, but nothing said what caused it or what it took with it.
//
//  These run against an in-memory container and a temporary log directory, so
//  they never touch the real store or the log on this machine.
//

import Foundation
import SwiftData
import Testing
@testable import ScoreCard

@MainActor
struct ActionLogRecorderTests {

    private func makeContainer() throws -> ModelContainer {
        let schema = Schema(ScoreCardSchema.models)
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
        return try ModelContainer(for: schema, configurations: [config])
    }

    private func makeLog() throws -> (ActionLog, URL) {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("recorder-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return (ActionLog(directory: directory), directory)
    }

    /// Entries settle on the log's serial queue; reading `totalBytes` syncs on
    /// that queue, so by the time it returns the writes have landed.
    private func settled(_ log: ActionLog) -> [ActionLogEntry] {
        _ = log.totalBytes
        return log.recentEntries()
    }

    @Test func creatingAndDeletingAPlayerIsRecordedWithItsTeams() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let (log, directory) = try makeLog()
        defer { try? FileManager.default.removeItem(at: directory) }

        let recorder = ActionLogRecorder(log: log, isEnabled: { true })
        recorder.start()
        defer { recorder.stop() }

        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        [alice, bob].forEach(context.insert)
        let team = Team(name: "Reds", members: [alice, bob])
        context.insert(team)
        try context.save()

        #expect(settled(log).contains { $0.action == "playerCreated" && $0.name == "Alice" })
        #expect(settled(log).contains { $0.action == "teamCreated" && $0.name == "Reds" })

        // The delete is the point: the team must still be named, which is only
        // possible because the recorder reads at willSave rather than didSave.
        context.delete(alice)
        try context.save()

        let deletion = settled(log).first { $0.action == "playerDeleted" }
        #expect(deletion?.name == "Alice")
        #expect(deletion?.detail?["teams"] == "Reds")
    }

    @Test func scoringIsRecordedWithTheGameItBelongsTo() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let (log, directory) = try makeLog()
        defer { try? FileManager.default.removeItem(at: directory) }

        let recorder = ActionLogRecorder(log: log, isEnabled: { true })
        recorder.start()
        defer { recorder.stop() }

        let alice = Player(name: "Alice")
        context.insert(alice)
        let game = Game(title: "Briscola")
        context.insert(game)
        let participant = GameParticipant(player: alice, sortIndex: 0)
        participant.game = game
        context.insert(participant)
        try context.save()

        let gameLine = settled(log).first { $0.action == "gameCreated" }
        let gameId = try #require(gameLine?.gameId)

        // Points added, then undone — both must be recorded, and both must
        // carry the game so an evening reads back as one game.
        let entry = ScoreEntry(points: 3)
        entry.participant = participant
        context.insert(entry)
        try context.save()

        let added = settled(log).first { $0.action == "scoreAdded" }
        #expect(added?.detail?["points"] == "3")
        #expect(added?.gameId == gameId)

        context.delete(entry)
        try context.save()

        let removed = settled(log).first { $0.action == "scoreDeleted" }
        #expect(removed?.gameId == gameId)
    }

    @Test func nothingIsRecordedOnceRecordingIsStopped() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let (log, directory) = try makeLog()
        defer { try? FileManager.default.removeItem(at: directory) }

        let recorder = ActionLogRecorder(log: log, isEnabled: { true })
        recorder.start()
        recorder.stop()

        context.insert(Player(name: "Ghost"))
        try context.save()

        #expect(settled(log).isEmpty)
    }
}
