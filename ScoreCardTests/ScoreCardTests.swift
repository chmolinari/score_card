//
//  ScoreCardTests.swift
//  ScoreCardTests
//
//  Domain-logic tests covering the scorekeeping requirements: players, teams,
//  games (with and without a target), scoring, undo, ranking, and history.
//

import Testing
import SwiftData
import Foundation
@testable import ScoreCard

// Serialized: each test builds its own SwiftData container, and running them in
// parallel races on store setup in the shared test host.
@MainActor
@Suite(.serialized)
struct ScoreCardTests {

    /// A fresh in-memory container so each test starts from a clean store and
    /// never touches CloudKit or on-disk data.
    ///
    /// Callers MUST keep the returned container alive for the duration of the
    /// test (bind it to a local `let`). A `ModelContext` does not keep its
    /// container from deallocating, and using a context after its container is
    /// gone traps inside SwiftData.
    private func makeContainer() throws -> ModelContainer {
        let schema = Schema(ScoreCardSchema.models)
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
        return try ModelContainer(for: schema, configurations: [config])
    }

    private func addPoints(_ points: Int, to participant: GameParticipant, in context: ModelContext) {
        let entry = ScoreEntry(points: points)
        entry.participant = participant
        context.insert(entry)
    }

    // MARK: Requirements 1 & 2 — players and teams

    @Test func playersAndTeamsRelate() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        [alice, bob].forEach(context.insert)

        let team = Team(name: "The Aces", members: [alice, bob])
        context.insert(team)
        try context.save()

        #expect(team.sortedMembers.count == 2)
        // Inverse relationship is maintained automatically by SwiftData.
        #expect(alice.sortedTeams.first?.name == "The Aces")
        #expect(team.rosterSummary == "Alice & Bob")
    }

    // MARK: Requirement 3 — games with and without a target

    @Test func openEndedGameHasNoTarget() throws {
        let game = Game(title: "Briscola")
        #expect(game.hasTarget == false)
        #expect(game.targetPoints == nil)
        #expect(game.isOpen)
    }

    @Test func targetGameKeepsTarget() throws {
        let game = Game(title: "Scopa", hasTarget: true, targetPoints: 11)
        #expect(game.hasTarget)
        #expect(game.targetPoints == 11)
    }

    @Test func targetIsClearedWhenDisabled() throws {
        // Passing a target while hasTarget is false must not retain it.
        let game = Game(title: "Briscola", hasTarget: false, targetPoints: 11)
        #expect(game.targetPoints == nil)
    }

    // MARK: Requirement 4 — adding points to players and teams

    @Test func scoringAccumulatesAndRanks() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        [alice, bob].forEach(context.insert)

        let game = Game(title: "Scopa", hasTarget: true, targetPoints: 11)
        context.insert(game)
        let pa = GameParticipant(player: alice, sortIndex: 0)
        let pb = GameParticipant(player: bob, sortIndex: 1)
        pa.game = game
        pb.game = game
        [pa, pb].forEach(context.insert)

        addPoints(3, to: pa, in: context)
        addPoints(5, to: pa, in: context)
        addPoints(4, to: pb, in: context)
        try context.save()

        #expect(pa.totalScore == 8)
        #expect(pb.totalScore == 4)
        // Ranking is highest-first.
        #expect(game.rankedParticipants.first?.displayName == "Alice")
        #expect(game.leader?.displayName == "Alice")
    }

    @Test func undoRemovesLastEntry() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        context.insert(alice)
        let game = Game(title: "Briscola")
        context.insert(game)
        let pa = GameParticipant(player: alice, sortIndex: 0)
        pa.game = game
        context.insert(pa)

        addPoints(10, to: pa, in: context)
        addPoints(5, to: pa, in: context)
        try context.save()
        #expect(pa.totalScore == 15)

        // Undo = delete the most recent entry.
        let newest = pa.sortedEntries.first!
        context.delete(newest)
        try context.save()
        #expect(pa.totalScore == 10)
    }

    @Test func targetDetection() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let team = Team(name: "Aces")
        context.insert(team)
        let game = Game(title: "Scopa", hasTarget: true, targetPoints: 11)
        context.insert(game)
        let p = GameParticipant(team: team, sortIndex: 0)
        p.game = game
        context.insert(p)

        addPoints(7, to: p, in: context)
        #expect(game.winnersAtTarget.isEmpty)
        addPoints(5, to: p, in: context)  // now 12 >= 11
        #expect(game.winnersAtTarget.map(\.displayName) == ["Aces"])
    }

    // MARK: Requirements 5 & 6 — open/close and history

    @Test func closingGameMovesItToHistory() throws {
        let game = Game(title: "Scopa", hasTarget: true, targetPoints: 11)
        #expect(game.isOpen)
        game.closedAt = .now
        #expect(game.isOpen == false)
    }

    // MARK: Requirement 7 — date/time and geolocation tagging

    @Test func gameIsStampedWithDateAndLocation() throws {
        let game = Game(title: "Scopa")
        // Date/time is stamped at creation.
        #expect(game.createdAt.timeIntervalSinceNow < 1)

        let location = CapturedLocation(latitude: 40.8518, longitude: 14.2681, placeName: "Napoli, Italy")
        game.apply(location: location)
        #expect(game.latitude == 40.8518)
        #expect(game.longitude == 14.2681)
        #expect(game.locationName == "Napoli, Italy")
        #expect(game.coordinate != nil)
    }

    // MARK: Tally — win/play record per player and team

    @Test func tallyCountsWinsPlaysAndInProgress() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        [alice, bob].forEach(context.insert)

        // Finished game 1: Alice beats Bob.
        let g1 = Game(title: "Scopa")
        context.insert(g1)
        let g1a = GameParticipant(player: alice, sortIndex: 0); g1a.game = g1; context.insert(g1a)
        let g1b = GameParticipant(player: bob, sortIndex: 1); g1b.game = g1; context.insert(g1b)
        addPoints(11, to: g1a, in: context)
        addPoints(7, to: g1b, in: context)
        g1.closedAt = .now

        // Finished game 2: Bob beats Alice.
        let g2 = Game(title: "Briscola")
        context.insert(g2)
        let g2a = GameParticipant(player: alice, sortIndex: 0); g2a.game = g2; context.insert(g2a)
        let g2b = GameParticipant(player: bob, sortIndex: 1); g2b.game = g2; context.insert(g2b)
        addPoints(40, to: g2a, in: context)
        addPoints(81, to: g2b, in: context)
        g2.closedAt = .now

        // Open game: counts only as in-progress for both.
        let g3 = Game(title: "Open one")
        context.insert(g3)
        let g3a = GameParticipant(player: alice, sortIndex: 0); g3a.game = g3; context.insert(g3a)
        let g3b = GameParticipant(player: bob, sortIndex: 1); g3b.game = g3; context.insert(g3b)
        try context.save()

        #expect(alice.tally.played == 2)
        #expect(alice.tally.won == 1)
        #expect(alice.tally.inProgress == 1)
        #expect(alice.tally.winPercentage == 50)

        #expect(bob.tally.played == 2)
        #expect(bob.tally.won == 1)
        #expect(bob.tally.inProgress == 1)
    }

    @Test func tallyCountsTiesAsWinForBoth() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let red = Team(name: "Red")
        let blue = Team(name: "Blue")
        [red, blue].forEach(context.insert)

        let game = Game(title: "Tie")
        context.insert(game)
        let r = GameParticipant(team: red, sortIndex: 0); r.game = game; context.insert(r)
        let b = GameParticipant(team: blue, sortIndex: 1); b.game = game; context.insert(b)
        addPoints(10, to: r, in: context)
        addPoints(10, to: b, in: context)
        game.closedAt = .now
        try context.save()

        #expect(red.tally.won == 1)
        #expect(blue.tally.won == 1)
    }

    @Test func emptyTallyForNewPlayer() throws {
        let player = Player(name: "Newbie")
        #expect(player.tally.isEmpty)
        #expect(player.tally.winPercentage == nil)
    }

    // MARK: Most-used ranking for the New Game selector

    @Test func frequentPickerRanksByUsageThenName() throws {
        struct Item { let name: String; let usage: Int }
        let items = [
            Item(name: "Zoe", usage: 5),
            Item(name: "Amy", usage: 5),   // ties with Zoe → name breaks tie
            Item(name: "Bob", usage: 9),
            Item(name: "Cal", usage: 0),   // unused → excluded
            Item(name: "Dan", usage: 1),
            Item(name: "Eve", usage: 2),
            Item(name: "Fox", usage: 3),
        ]
        let top = FrequentPicker.top(items, limit: 5, usage: { $0.usage }, name: { $0.name })

        #expect(top.map(\.name) == ["Bob", "Amy", "Zoe", "Fox", "Eve"])
        #expect(top.count == 5)               // capped at the limit
        #expect(!top.contains { $0.name == "Cal" })  // zero-usage excluded
    }

    @Test func usageCountReflectsParticipations() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        context.insert(alice)
        #expect(alice.usageCount == 0)

        for title in ["G1", "G2", "G3"] {
            let game = Game(title: title)
            context.insert(game)
            let p = GameParticipant(player: alice, sortIndex: 0)
            p.game = game
            context.insert(p)
        }
        try context.save()
        #expect(alice.usageCount == 3)
    }

    // MARK: Backup / restore / reset

    @Test func eraseAllRemovesEverything() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        context.insert(alice)
        let team = Team(name: "Aces", members: [alice])
        context.insert(team)
        let game = Game(title: "Scopa")
        context.insert(game)
        let p = GameParticipant(team: team, sortIndex: 0); p.game = game; context.insert(p)
        addPoints(5, to: p, in: context)
        try context.save()

        try BackupService.eraseAll(in: context)

        #expect(try context.fetch(FetchDescriptor<Player>()).isEmpty)
        #expect(try context.fetch(FetchDescriptor<Team>()).isEmpty)
        #expect(try context.fetch(FetchDescriptor<Game>()).isEmpty)
        #expect(try context.fetch(FetchDescriptor<GameParticipant>()).isEmpty)
        #expect(try context.fetch(FetchDescriptor<ScoreEntry>()).isEmpty)
    }

    @Test func backupRoundTripRebuildsData() throws {
        // Build a representative store.
        let sourceContainer = try makeContainer()
        let source = sourceContainer.mainContext
        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        [alice, bob].forEach(source.insert)
        let aces = Team(name: "Aces", members: [alice, bob])
        source.insert(aces)

        let scopa = Game(title: "Scopa", hasTarget: true, targetPoints: 11)
        scopa.locationName = "Napoli, Italy"
        scopa.latitude = 40.8518
        scopa.longitude = 14.2681
        source.insert(scopa)
        let teamSide = GameParticipant(team: aces, sortIndex: 0); teamSide.game = scopa; source.insert(teamSide)
        let soloSide = GameParticipant(player: alice, sortIndex: 1); soloSide.game = scopa; source.insert(soloSide)
        addPoints(11, to: teamSide, in: source)
        addPoints(4, to: soloSide, in: source)
        addPoints(3, to: soloSide, in: source)
        scopa.closedAt = .now
        try source.save()

        // Export from the source store.
        let data = try BackupService.exportData(from: source)

        // Restore into a fresh, separate store.
        let destContainer = try makeContainer()
        let dest = destContainer.mainContext
        let snapshot = try BackupService.decodeSnapshot(data)
        try BackupService.restore(snapshot, into: dest)

        let players = try dest.fetch(FetchDescriptor<Player>(sortBy: [SortDescriptor(\.name)]))
        let teams = try dest.fetch(FetchDescriptor<Team>())
        let games = try dest.fetch(FetchDescriptor<Game>())

        #expect(players.map(\.name) == ["Alice", "Bob"])
        #expect(teams.first?.sortedMembers.map(\.name) == ["Alice", "Bob"])
        #expect(games.count == 1)

        let restored = try #require(games.first)
        #expect(restored.title == "Scopa")
        #expect(restored.targetPoints == 11)
        #expect(restored.locationName == "Napoli, Italy")
        #expect(restored.isOpen == false)

        let ranked = restored.rankedParticipants
        #expect(ranked.count == 2)
        #expect(ranked.first?.displayName == "Aces")
        #expect(ranked.first?.totalScore == 11)
        #expect(ranked.last?.totalScore == 7)   // 4 + 3
        // The team participant is relinked to the restored team.
        #expect(ranked.first?.isTeam == true)
    }

    @Test func restoreReplacesExistingData() throws {
        // Source has one player.
        let sourceContainer = try makeContainer()
        let source = sourceContainer.mainContext
        source.insert(Player(name: "OnlyInBackup"))
        try source.save()
        let data = try BackupService.exportData(from: source)

        // Destination already has different data that must be wiped.
        let destContainer = try makeContainer()
        let dest = destContainer.mainContext
        dest.insert(Player(name: "Stale1"))
        dest.insert(Player(name: "Stale2"))
        try dest.save()

        try BackupService.restore(BackupService.decodeSnapshot(data), into: dest)

        let names = try dest.fetch(FetchDescriptor<Player>()).map(\.name)
        #expect(names == ["OnlyInBackup"])
    }

    @Test func decodingRejectsNonBackupData() throws {
        let garbage = Data("not a backup".utf8)
        #expect(throws: BackupError.self) {
            _ = try BackupService.decodeSnapshot(garbage)
        }
    }

    @Test func historySurvivesPlayerDeletion() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        context.insert(alice)
        let game = Game(title: "Briscola")
        context.insert(game)
        let pa = GameParticipant(player: alice, sortIndex: 0)
        pa.game = game
        context.insert(pa)
        try context.save()

        // Deleting the player nullifies the link but keeps the snapshot name.
        context.delete(alice)
        try context.save()
        #expect(pa.player == nil)
        #expect(pa.displayName == "Alice")
    }
}
