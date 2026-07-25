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

    @Test func tallyCountsTiesAsDrawForBoth() throws {
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

        // A shared top score is a draw, not a win, for everyone tied.
        #expect(game.isDraw)
        #expect(red.tally.won == 0)
        #expect(red.tally.drawn == 1)
        #expect(red.tally.played == 1)
        #expect(blue.tally.won == 0)
        #expect(blue.tally.drawn == 1)
    }

    @Test func drawOnlyCountsForTiedTopScorers() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let a = Player(name: "A")
        let b = Player(name: "B")
        let c = Player(name: "C")
        [a, b, c].forEach(context.insert)

        // A and B tie for the top at 10; C trails at 4.
        let game = Game(title: "Three-way")
        context.insert(game)
        let pa = GameParticipant(player: a, sortIndex: 0); pa.game = game; context.insert(pa)
        let pb = GameParticipant(player: b, sortIndex: 1); pb.game = game; context.insert(pb)
        let pc = GameParticipant(player: c, sortIndex: 2); pc.game = game; context.insert(pc)
        addPoints(10, to: pa, in: context)
        addPoints(10, to: pb, in: context)
        addPoints(4, to: pc, in: context)
        game.closedAt = .now
        try context.save()

        #expect(game.isDraw)
        // The two leaders drew; the trailing player neither won nor drew.
        #expect(a.tally.drawn == 1)
        #expect(b.tally.drawn == 1)
        #expect(c.tally.drawn == 0)
        #expect(c.tally.won == 0)
        #expect(c.tally.played == 1)
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

    // MARK: Below-zero scoring policy

    @Test func negativeScoreClampStopsAtZero() {
        // Additions always pass through, whatever the policy.
        #expect(NegativeScores.effectiveDelta(points: 3, currentTotal: 0, allowNegative: false) == 3)
        #expect(NegativeScores.effectiveDelta(points: 3, currentTotal: 5, allowNegative: true) == 3)

        // Clamping (default): a subtraction is reduced so the total stops at zero.
        #expect(NegativeScores.effectiveDelta(points: -5, currentTotal: 2, allowNegative: false) == -2)
        // Exact subtraction down to zero is unchanged.
        #expect(NegativeScores.effectiveDelta(points: -2, currentTotal: 2, allowNegative: false) == -2)
        // Already at zero: subtraction is dropped entirely (never flips to an addition).
        #expect(NegativeScores.effectiveDelta(points: -5, currentTotal: 0, allowNegative: false) == 0)
        #expect(NegativeScores.effectiveDelta(points: -5, currentTotal: -3, allowNegative: false) == 0)

        // When below-zero is allowed, subtraction passes through unchanged.
        #expect(NegativeScores.effectiveDelta(points: -5, currentTotal: 2, allowNegative: true) == -5)
    }

    /// A total the user supplies outright obeys the same preference a
    /// subtraction does — it is not a back door past the user's choice.
    @Test func suppliedTotalsFollowTheSamePolicy() {
        #expect(NegativeScores.clamped(-1, allowNegative: false) == 0)
        #expect(NegativeScores.clamped(-40, allowNegative: false) == 0)
        #expect(NegativeScores.clamped(0, allowNegative: false) == 0)
        #expect(NegativeScores.clamped(21, allowNegative: false) == 21)

        #expect(NegativeScores.clamped(-1, allowNegative: true) == -1)
        #expect(NegativeScores.clamped(-40, allowNegative: true) == -40)
        #expect(NegativeScores.clamped(21, allowNegative: true) == 21)
    }

    // MARK: Players/Teams list ordering

    /// A stand-in for a player or team: the sorter only needs a name and a tally.
    private struct SortableCompetitor {
        let name: String
        let tally: Tally
    }

    @Test func competitorSorterOrdersByNameInBothDirections() {
        let items = [
            SortableCompetitor(name: "Bob", tally: Tally()),
            SortableCompetitor(name: "alice", tally: Tally()),      // lower-case sorts with "A"
            SortableCompetitor(name: "Player 10", tally: Tally()),
            SortableCompetitor(name: "Player 2", tally: Tally()),   // numeric-aware: 2 before 10
        ]

        let ascending = CompetitorSorter.sorted(items, by: .nameAscending,
                                                name: { $0.name }, tally: { $0.tally })
        #expect(ascending.map(\.name) == ["alice", "Bob", "Player 2", "Player 10"])

        let descending = CompetitorSorter.sorted(items, by: .nameDescending,
                                                 name: { $0.name }, tally: { $0.tally })
        #expect(descending.map(\.name) == ["Player 10", "Player 2", "Bob", "alice"])
    }

    @Test func competitorSorterRanksByWinsThenWinPercentThenName() {
        let items = [
            SortableCompetitor(name: "Cara", tally: Tally(played: 4, won: 2)),   // 2 wins, 50%
            SortableCompetitor(name: "Abe",  tally: Tally(played: 10, won: 5)),  // 5 wins
            SortableCompetitor(name: "Bea",  tally: Tally(played: 2, won: 2)),   // 2 wins, 100%
            SortableCompetitor(name: "Dan",  tally: Tally(played: 4, won: 2)),   // ties Cara → name breaks it
            SortableCompetitor(name: "Eve",  tally: Tally()),                    // no games → last
        ]

        let descending = CompetitorSorter.sorted(items, by: .scoreDescending,
                                                 name: { $0.name }, tally: { $0.tally })
        // Most wins first; within the 2-win group, higher win% first (Bea 100%
        // before the 50% pair), the 50% pair alphabetical; no-games last.
        #expect(descending.map(\.name) == ["Abe", "Bea", "Cara", "Dan", "Eve"])

        let ascending = CompetitorSorter.sorted(items, by: .scoreAscending,
                                                name: { $0.name }, tally: { $0.tally })
        // Fewest wins first (no-games has zero wins → first), then lower win%.
        #expect(ascending.map(\.name) == ["Eve", "Cara", "Dan", "Bea", "Abe"])
    }

    @Test func competitorSorterRanksUnplayedBelowAnAllLossRecord() {
        let items = [
            SortableCompetitor(name: "Ghost", tally: Tally()),                    // never played → nil %
            SortableCompetitor(name: "Loser", tally: Tally(played: 3, won: 0)),   // played, 0%
        ]
        let descending = CompetitorSorter.sorted(items, by: .scoreDescending,
                                                 name: { $0.name }, tally: { $0.tally })
        // Both have zero wins, but a real 0% record outranks "no games yet".
        #expect(descending.map(\.name) == ["Loser", "Ghost"])
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

    // MARK: Dealer / seating

    @Test func dealerRotatesCounterClockwiseAndWraps() throws {
        let container = try makeContainer()
        let context = container.mainContext
        var players: [Player] = []
        for name in ["A", "B", "C", "D"] {
            let player = Player(name: name); context.insert(player); players.append(player)
        }
        let game = Game(title: "Briscola"); context.insert(game)
        for (position, player) in players.enumerated() {
            let seat = Seat(player: player, position: position); seat.game = game; context.insert(seat)
        }
        game.currentDealerIndex = 0
        try context.save()

        #expect(game.currentDealer?.name == "A")
        #expect(game.nextDealer(.counterClockwise)?.name == "B")
        game.advanceDealer(.counterClockwise)
        #expect(game.currentDealer?.name == "B")
        game.advanceDealer(.counterClockwise); game.advanceDealer(.counterClockwise)
        #expect(game.currentDealer?.name == "D")
        game.advanceDealer(.counterClockwise)      // wraps around the table
        #expect(game.currentDealer?.name == "A")
    }

    @Test func dealerRotatesClockwiseAndWraps() throws {
        let container = try makeContainer()
        let context = container.mainContext
        var players: [Player] = []
        for name in ["A", "B", "C", "D"] {
            let player = Player(name: name); context.insert(player); players.append(player)
        }
        let game = Game(title: "Scopa"); context.insert(game)
        for (position, player) in players.enumerated() {
            let seat = Seat(player: player, position: position); seat.game = game; context.insert(seat)
        }
        game.currentDealerIndex = 0
        try context.save()

        #expect(game.currentDealer?.name == "A")
        #expect(game.nextDealer(.clockwise)?.name == "D")   // clockwise = previous seat
        game.advanceDealer(.clockwise)                       // wraps backwards
        #expect(game.currentDealer?.name == "D")
        game.advanceDealer(.clockwise)
        #expect(game.currentDealer?.name == "C")
    }

    @Test func gameWithoutSeatingHasNoDealer() throws {
        let game = Game(title: "Scopa")
        #expect(game.hasSeating == false)
        #expect(game.currentDealer == nil)
    }

    // MARK: Scoreboard order follows the dealing rotation, not the score

    @Test func participantsOrderByDealingRotation() throws {
        let container = try makeContainer()
        let context = container.mainContext
        var players: [Player] = []
        for name in ["A", "B", "C", "D"] {
            let player = Player(name: name); context.insert(player); players.append(player)
        }
        let game = Game(title: "Briscola"); context.insert(game)
        var parts: [GameParticipant] = []
        for (i, player) in players.enumerated() {
            let p = GameParticipant(player: player, sortIndex: i); p.game = game; context.insert(p)
            parts.append(p)
        }
        for (position, player) in players.enumerated() {
            let seat = Seat(player: player, position: position); seat.game = game; context.insert(seat)
        }
        game.currentDealerIndex = 0
        try context.save()

        // Give the trailing seat the highest score to prove order ignores it.
        addPoints(99, to: parts[3], in: context)

        // Counter-clockwise keeps the seat order: first dealer (A) on top.
        #expect(game.participantsInDealingOrder(.counterClockwise).map(\.displayName) == ["A", "B", "C", "D"])
        // Clockwise: first dealer still on top, the rest follow the deal backwards.
        #expect(game.participantsInDealingOrder(.clockwise).map(\.displayName) == ["A", "D", "C", "B"])
    }

    @Test func teamsOrderByEarliestDealingMember() throws {
        let container = try makeContainer()
        let context = container.mainContext
        var players: [Player] = []
        for name in ["A", "B", "C", "D"] {
            let player = Player(name: name); context.insert(player); players.append(player)
        }
        // Two teams; B (seat 1) is the first of Reds to deal, A (seat 0) for Blues.
        let blues = Team(name: "Blues", members: [players[0], players[2]])  // A, C
        let reds = Team(name: "Reds", members: [players[1], players[3]])     // B, D
        [blues, reds].forEach(context.insert)
        let game = Game(title: "Tressette"); context.insert(game)
        let pBlues = GameParticipant(team: blues, sortIndex: 0); pBlues.game = game
        let pReds = GameParticipant(team: reds, sortIndex: 1); pReds.game = game
        [pBlues, pReds].forEach(context.insert)
        for (position, player) in players.enumerated() {
            let seat = Seat(player: player, position: position); seat.game = game; context.insert(seat)
        }
        game.currentDealerIndex = 0
        try context.save()

        // First dealer is A (Blues), so Blues is on top counter-clockwise.
        #expect(game.participantsInDealingOrder(.counterClockwise).map(\.displayName) == ["Blues", "Reds"])
        // Clockwise the deal goes A → D(Reds) next, so Reds' earliest member (D)
        // outranks Blues' next member (C): the team with A still leads, though.
        #expect(game.participantsInDealingOrder(.clockwise).map(\.displayName) == ["Blues", "Reds"])
    }

    @Test func dealingOrderFallsBackToAddedOrderWithoutSeating() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice"); let bob = Player(name: "Bob")
        [alice, bob].forEach(context.insert)
        let game = Game(title: "Briscola"); context.insert(game)
        let pa = GameParticipant(player: alice, sortIndex: 0); pa.game = game
        let pb = GameParticipant(player: bob, sortIndex: 1); pb.game = game
        [pa, pb].forEach(context.insert)
        addPoints(50, to: pb, in: context)  // higher score must not reorder
        try context.save()

        #expect(game.participantsInDealingOrder(.counterClockwise).map(\.displayName) == ["Alice", "Bob"])
    }

    @Test func draftExpandsTeamsToPeopleAndDedupes() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        let carol = Player(name: "Carol")
        [alice, bob, carol].forEach(context.insert)
        let team = Team(name: "Aces", members: [alice, bob])
        context.insert(team)
        try context.save()

        // Team + a solo entry for Alice (already on the team) + solo Carol.
        let draft = GameDraft(title: "G", hasTarget: false, targetPoints: nil,
                              competitors: [.team(team), .player(alice), .player(carol)])
        #expect(draft.people.map(\.name) == ["Alice", "Bob", "Carol"])
    }

    @Test func backupPreservesSeatsAndDealer() throws {
        let sourceContainer = try makeContainer()
        let source = sourceContainer.mainContext
        var players: [Player] = []
        for name in ["A", "B", "C"] {
            let player = Player(name: name); source.insert(player); players.append(player)
        }
        let game = Game(title: "Tressette"); source.insert(game)
        let participant = GameParticipant(player: players[0], sortIndex: 0)
        participant.game = game; source.insert(participant)
        for (position, player) in players.enumerated() {
            let seat = Seat(player: player, position: position); seat.game = game; source.insert(seat)
        }
        game.currentDealerIndex = 1
        try source.save()

        let data = try BackupService.exportData(from: source)
        let destContainer = try makeContainer()
        let dest = destContainer.mainContext
        try BackupService.restore(BackupService.decodeSnapshot(data), into: dest)

        let restored = try #require(try dest.fetch(FetchDescriptor<Game>()).first)
        #expect(restored.orderedSeats.map { $0.player?.name } == ["A", "B", "C"])
        #expect(restored.currentDealerIndex == 1)
        #expect(restored.currentDealer?.name == "B")
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

    // MARK: Editable game-name list + last-used default

    @Test func gameNameDefaults() throws {
        let gameName = GameName(name: "Scopa")
        #expect(gameName.name == "Scopa")
        #expect(gameName.lastUsedAt == .distantPast)   // never used yet
    }

    /// The default pre-selection is the most recently used name; never-used names
    /// (all sharing `.distantPast`) and ties fall back to alphabetical order.
    @Test func defaultSelectionPicksMostRecentlyUsed() {
        let scopa = GameName(name: "Scopa", lastUsedAt: Date(timeIntervalSince1970: 100))
        let briscola = GameName(name: "Briscola", lastUsedAt: Date(timeIntervalSince1970: 500))
        let tresette = GameName(name: "Tresette")   // never used

        let pick = GameNamePicker.defaultSelection([scopa, briscola, tresette],
                                                   lastUsed: { $0.lastUsedAt },
                                                   name: { $0.name })
        #expect(pick?.name == "Briscola")

        // All unused → alphabetical.
        let a = GameName(name: "Zilch"), b = GameName(name: "Alpha")
        let tiePick = GameNamePicker.defaultSelection([a, b],
                                                      lastUsed: { $0.lastUsedAt },
                                                      name: { $0.name })
        #expect(tiePick?.name == "Alpha")

        #expect(GameNamePicker.defaultSelection([] as [GameName],
                                                lastUsed: { $0.lastUsedAt },
                                                name: { $0.name }) == nil)
    }

    /// Seeding mines distinct titles from existing games (case-insensitively),
    /// stamping each name with the most recent matching game's creation date.
    @Test func seedingBuildsDistinctNamesFromGames() throws {
        let container = try makeContainer()
        let context = container.mainContext

        let older = Game(title: "scopa")        // same name, different case + older
        older.createdAt = Date(timeIntervalSince1970: 100)
        let newer = Game(title: "Scopa")        // most recent spelling wins
        newer.createdAt = Date(timeIntervalSince1970: 900)
        let briscola = Game(title: "Briscola")
        briscola.createdAt = Date(timeIntervalSince1970: 300)
        [older, newer, briscola].forEach(context.insert)
        try context.save()

        GameName.seedFromExistingGames(context: context)

        let names = try context.fetch(FetchDescriptor<GameName>(sortBy: [SortDescriptor(\.name)]))
        #expect(names.map(\.name) == ["Briscola", "Scopa"])   // de-duped, newest spelling
        let scopa = try #require(names.first { $0.name == "Scopa" })
        #expect(scopa.lastUsedAt == Date(timeIntervalSince1970: 900))   // latest matching game
    }

    @Test func seedingIsSkippedWhenNamesAlreadyExist() throws {
        let container = try makeContainer()
        let context = container.mainContext
        context.insert(GameName(name: "Existing"))
        let game = Game(title: "Scopa")
        context.insert(game)
        try context.save()

        GameName.seedFromExistingGames(context: context)

        let names = try context.fetch(FetchDescriptor<GameName>())
        #expect(names.map(\.name) == ["Existing"])   // untouched; no game mined
    }

    /// A game name is just a template: deleting it must not affect games that
    /// already copied their title from it.
    @Test func deletingGameNameLeavesGamesUntouched() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let gameName = GameName(name: "Scopa")
        let game = Game(title: "Scopa")
        context.insert(gameName)
        context.insert(game)
        try context.save()

        context.delete(gameName)
        try context.save()

        let games = try context.fetch(FetchDescriptor<Game>())
        #expect(games.map(\.title) == ["Scopa"])
        #expect(try context.fetch(FetchDescriptor<GameName>()).isEmpty)
    }

    @Test func backupRoundTripIncludesGameNames() throws {
        let sourceContainer = try makeContainer()
        let source = sourceContainer.mainContext
        source.insert(GameName(name: "Scopa", lastUsedAt: Date(timeIntervalSince1970: 700)))
        source.insert(GameName(name: "Briscola"))
        try source.save()

        let data = try BackupService.exportData(from: source)
        let destContainer = try makeContainer()
        let dest = destContainer.mainContext
        try BackupService.restore(BackupService.decodeSnapshot(data), into: dest)

        let restored = try dest.fetch(FetchDescriptor<GameName>(sortBy: [SortDescriptor(\.name)]))
        #expect(restored.map(\.name) == ["Briscola", "Scopa"])
        let scopa = try #require(restored.first { $0.name == "Scopa" })
        #expect(scopa.lastUsedAt == Date(timeIntervalSince1970: 700))
    }

    // MARK: - Registering a past game

    @Test func registeredGameIsClosedAndBackdated() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        [alice, bob].forEach(context.insert)

        let playedAt = Date(timeIntervalSince1970: 1_500_000_000)
        let game = try GameRegistration.register(title: "Scopa",
                                                 finalScores: [.init(competitor: .player(alice), points: 21),
                                                               .init(competitor: .player(bob), points: 15)],
                                                 playedAt: playedAt,
                                                 locationName: "Nonna's house",
                                                 in: context)

        #expect(game.isOpen == false)
        #expect(game.createdAt == playedAt)
        #expect(game.closedAt == playedAt)
        #expect(game.hasTarget == false)
        #expect(game.targetPoints == nil)
        #expect(game.hasSeating == false)
        #expect(game.locationName == "Nonna's house")
        #expect(game.latitude == nil && game.longitude == nil)
        let entries = (game.participants ?? []).flatMap { $0.scoreEntries ?? [] }
        #expect(entries.count == 2)
        #expect(entries.allSatisfy { $0.timestamp == playedAt })
    }

    @Test func registeredGameRanksWinnersAndFeedsTallies() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        [alice, bob].forEach(context.insert)

        let game = try GameRegistration.register(title: "Scopa",
                                                 finalScores: [.init(competitor: .player(alice), points: 21),
                                                               .init(competitor: .player(bob), points: 15)],
                                                 playedAt: .now,
                                                 locationName: nil,
                                                 in: context)

        let winner = try #require(game.rankedParticipants.first)
        #expect(winner.displayName == "Alice")
        #expect(winner.isWinner)
        #expect(alice.tally.played == 1)
        #expect(alice.tally.won == 1)
        #expect(bob.tally.played == 1)
        #expect(bob.tally.won == 0)
        // usageCount derives from participations, so registration counts too.
        #expect(alice.usageCount == 1)
        #expect(bob.usageCount == 1)
    }

    @Test func registeredGameTiedFinalsCountAsDraw() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let red = Team(name: "Red")
        let blue = Team(name: "Blue")
        [red, blue].forEach(context.insert)

        let game = try GameRegistration.register(title: "Tressette",
                                                 finalScores: [.init(competitor: .team(red), points: 10),
                                                               .init(competitor: .team(blue), points: 10)],
                                                 playedAt: .now,
                                                 locationName: nil,
                                                 in: context)

        #expect(game.isDraw)
        #expect(red.tally.drawn == 1)
        #expect(red.tally.won == 0)
        #expect(blue.tally.drawn == 1)
        #expect(blue.tally.won == 0)
    }

    @Test func registeredGameFollowsTheBelowZeroPreference() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        [alice, bob].forEach(context.insert)

        let game = try GameRegistration.register(title: "Cirulla",
                                                 finalScores: [.init(competitor: .player(alice), points: -5),
                                                               .init(competitor: .player(bob), points: 0)],
                                                 playedAt: .now,
                                                 locationName: nil,
                                                 in: context)

        // Registering defaults to clamping, like every other scoring path, so
        // the -5 lands on 0 and the two competitors tie at zero.
        let scores = game.rankedParticipants.map(\.totalScore)
        #expect(scores == [0, 0])

        // With below-zero allowed, the transcribed total is kept verbatim.
        let negative = try GameRegistration.register(title: "Spades",
                                                     finalScores: [.init(competitor: .player(alice), points: -5),
                                                                   .init(competitor: .player(bob), points: 0)],
                                                     playedAt: .now,
                                                     locationName: nil,
                                                     allowNegativeScores: true,
                                                     in: context)
        #expect(negative.rankedParticipants.map(\.totalScore) == [0, -5])
    }

    @Test func registeredGameOrdersByPlayedDateInHistory() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        [alice, bob].forEach(context.insert)

        let fresh = Game(title: "Today's game")
        context.insert(fresh)

        let yearAgo = Date.now.addingTimeInterval(-365 * 24 * 3600)
        try GameRegistration.register(title: "Last year's game",
                                      finalScores: [.init(competitor: .player(alice), points: 21),
                                                    .init(competitor: .player(bob), points: 15)],
                                      playedAt: yearAgo,
                                      locationName: nil,
                                      in: context)

        // Same sort the Games tab uses: createdAt, newest first.
        let games = try context.fetch(FetchDescriptor<Game>(sortBy: [SortDescriptor(\.createdAt, order: .reverse)]))
        #expect(games.map(\.title) == ["Today's game", "Last year's game"])
    }

    @Test func registeredGameNormalizesLocation() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        [alice, bob].forEach(context.insert)

        let scores: [GameRegistration.FinalScore] = [.init(competitor: .player(alice), points: 1),
                                                     .init(competitor: .player(bob), points: 2)]

        let blank = try GameRegistration.register(title: "A", finalScores: scores,
                                                  playedAt: .now, locationName: "   ", in: context)
        #expect(blank.locationName == nil)

        let padded = try GameRegistration.register(title: "B", finalScores: scores,
                                                   playedAt: .now, locationName: "  Nonna's house  ", in: context)
        #expect(padded.locationName == "Nonna's house")
    }

    /// The filing date honors how much the user remembers: full date + time
    /// verbatim, date only at the start of that day, and neither means "now".
    @Test func playedDateHonorsDateAndTimeOptOuts() throws {
        let calendar = Calendar.current
        let now = Date(timeIntervalSince1970: 1_750_000_000)
        let selection = try #require(calendar.date(from: DateComponents(year: 2024, month: 6, day: 14,
                                                                        hour: 18, minute: 45)))

        // No date at all → the registration moment.
        #expect(GameRegistration.playedDate(hasDate: false, hasTime: false,
                                            selection: selection, now: now, calendar: calendar) == now)
        // Date without a time → the exact start of that day.
        #expect(GameRegistration.playedDate(hasDate: true, hasTime: false,
                                            selection: selection, now: now, calendar: calendar)
                == calendar.startOfDay(for: selection))
        // Date and time → verbatim.
        #expect(GameRegistration.playedDate(hasDate: true, hasTime: true,
                                            selection: selection, now: now, calendar: calendar) == selection)
    }

    /// A stamp at exactly midnight means "time unknown" (date-only
    /// registration), so the formatter must not show a fabricated 00:00.
    @Test func dateTimeFormattingOmitsTimeAtExactMidnight() throws {
        let calendar = Calendar.current
        let midnight = calendar.startOfDay(for: Date(timeIntervalSince1970: 1_700_000_000))
        let afternoon = try #require(calendar.date(byAdding: .minute, value: 870, to: midnight))  // 14:30

        #expect(GameFormatting.dateTime(midnight) == midnight.formatted(date: .abbreviated, time: .omitted))
        #expect(GameFormatting.dateTime(afternoon) == afternoon.formatted(date: .abbreviated, time: .shortened))
        #expect(GameFormatting.dateTime(midnight) != midnight.formatted(date: .abbreviated, time: .shortened))
    }

    /// The selection semantics shared by New Game and Register Past Game:
    /// team games and player games never mix, duplicates are ignored, and
    /// toggling an existing selection removes it.
    @Test func competitorSelectionRulesEnforceTeamExclusivity() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        let team = Team(name: "The Aces")
        [alice, bob].forEach(context.insert)
        context.insert(team)

        var selection: [GameCompetitor] = []
        selection = CompetitorSelectionRules.toggling(.player(alice), in: selection)
        selection = CompetitorSelectionRules.toggling(.player(bob), in: selection)
        #expect(selection == [.player(alice), .player(bob)])

        // Selecting a team turns this into a team game: players are dropped.
        selection = CompetitorSelectionRules.toggling(.team(team), in: selection)
        #expect(selection == [.team(team)])

        // Adding an already-selected competitor is a no-op.
        selection = CompetitorSelectionRules.adding(.team(team), to: selection)
        #expect(selection == [.team(team)])

        // Toggling an existing selection removes it.
        selection = CompetitorSelectionRules.toggling(.team(team), in: selection)
        #expect(selection.isEmpty)
    }

    // MARK: - Editing a closed game

    /// The below-zero preference applies to an edited total exactly as it does to
    /// live scoring: off, nothing lands under zero; on, negatives pass through.
    @Test func editedTotalHonorsBelowZeroPolicy() {
        // Clamping (default): anything negative lands on zero instead.
        #expect(GameScoreEdit.normalizedTotal(-1, allowNegative: false) == 0)
        #expect(GameScoreEdit.normalizedTotal(-40, allowNegative: false) == 0)
        #expect(GameScoreEdit.normalizedTotal(0, allowNegative: false) == 0)

        // Allowed: stored verbatim, sign and all.
        
        

        // Positives are never touched, whatever the policy.
        #expect(GameScoreEdit.normalizedTotal(21, allowNegative: false) == 21)
        #expect(GameScoreEdit.normalizedTotal(-1, allowNegative: true) == -1)
        #expect(GameScoreEdit.normalizedTotal(-40, allowNegative: true) == -40)
        
    }

    /// A total is the sum of its entries, so an edit is applied by appending one
    /// more entry — this is the value that entry has to carry.
    @Test func editDeltaMovesATotalInBothDirections() {
        #expect(GameScoreEdit.delta(from: 10, to: 15) == 5)      // raising
        #expect(GameScoreEdit.delta(from: 15, to: 10) == -5)     // lowering
        #expect(GameScoreEdit.delta(from: 10, to: 10) == 0)      // unchanged
        #expect(GameScoreEdit.delta(from: -3, to: 4) == 7)       // across zero
        #expect(GameScoreEdit.delta(from: 0, to: -6) == -6)
    }

    /// Requirement: a game counts as edited only when the final score actually
    /// differs from the one it had before.
    @Test func editIsDetectedOnlyWhenATotalDiffers() {
        #expect(GameScoreEdit.isChanged(before: [11, 7], after: [11, 7]) == false)
        #expect(GameScoreEdit.isChanged(before: [], after: []) == false)

        #expect(GameScoreEdit.isChanged(before: [11, 7], after: [12, 7]))   // first moved
        #expect(GameScoreEdit.isChanged(before: [11, 7], after: [11, 8]))   // second moved
        #expect(GameScoreEdit.isChanged(before: [11, 7], after: [8, 12]))   // both moved
    }

    /// Replicates what `GameEditView.save()` commits: one delta entry per changed
    /// competitor, one `GameEdit` carrying the motivation, and a game that stays
    /// closed.
    @Test func editingClosedGameCorrectsScoresAndRecordsTheReason() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        [alice, bob].forEach(context.insert)

        let game = Game(title: "Scopa")
        context.insert(game)
        let pa = GameParticipant(player: alice, sortIndex: 0); pa.game = game; context.insert(pa)
        let pb = GameParticipant(player: bob, sortIndex: 1); pb.game = game; context.insert(pb)
        addPoints(11, to: pa, in: context)
        addPoints(7, to: pb, in: context)
        let closedAt = Date(timeIntervalSince1970: 1_600_000_000)
        game.closedAt = closedAt
        try context.save()

        // What the editor freezes when the sheet opens.
        let participants = game.rankedParticipants
        #expect(participants.map(\.totalScore) == [11, 7])
        // Alice's 11 was really a 9; Bob's score is left alone.
        let proposedTotals = [9, 7]

        // Drives the very same commit the editor's Save button runs, so the two
        // cannot drift apart while this test keeps passing.
        let recorded = game.applyScoreEdit(reason: "Miscounted the last scopa",
                                           proposedTotals: proposedTotals,
                                           for: participants,
                                           in: context)
        #expect(recorded)
        try context.save()

        // The appended entry lands the total exactly on the requested one.
        #expect(participants.map(\.totalScore) == proposedTotals)
        // History is appended to, never rewritten: Alice now has two entries.
        #expect((participants[0].scoreEntries ?? []).count == 2)
        #expect((participants[1].scoreEntries ?? []).count == 1)

        #expect(game.isEdited)
        #expect(game.sortedEdits.map(\.reason) == ["Miscounted the last scopa"])
        #expect(game.lastEditedAt == game.sortedEdits.first?.editedAt)
        // An edit corrects a finished game; it never reopens it.
        #expect(game.closedAt == closedAt)
        #expect(game.isOpen == false)
    }

    /// An unchanged game must come out of the editor with nothing persisted at
    /// all — no score entry and no edit record.
    @Test func editWithoutAChangeRecordsNothing() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        let bob = Player(name: "Bob")
        [alice, bob].forEach(context.insert)

        let game = Game(title: "Briscola")
        context.insert(game)
        let pa = GameParticipant(player: alice, sortIndex: 0); pa.game = game; context.insert(pa)
        let pb = GameParticipant(player: bob, sortIndex: 1); pb.game = game; context.insert(pb)
        addPoints(61, to: pa, in: context)
        addPoints(59, to: pb, in: context)
        game.closedAt = .now
        try context.save()

        let entriesBefore = try context.fetch(FetchDescriptor<ScoreEntry>()).count
        let participants = game.rankedParticipants

        // The user typed a reason, then either changed nothing or changed a
        // total and put it back. Both reach the commit with totals matching the
        // current ones, and the commit itself — not just the disabled Save
        // button — has to refuse them.
        let recorded = game.applyScoreEdit(reason: "Should never be recorded",
                                           proposedTotals: [61, 59],
                                           for: participants,
                                           in: context)
        #expect(recorded == false)
        try context.save()

        #expect(try context.fetch(FetchDescriptor<ScoreEntry>()).count == entriesBefore)
        #expect(try context.fetch(FetchDescriptor<GameEdit>()).isEmpty)
        #expect(game.isEdited == false)
        #expect(game.lastEditedAt == nil)
        #expect(participants.map(\.totalScore) == [61, 59])
    }

    /// A totals list that doesn't line up with the competitors is refused rather
    /// than applied, so a correction can never land on the wrong competitor.
    @Test func editWithMismatchedTotalsIsRefused() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let alice = Player(name: "Alice")
        context.insert(alice)

        let game = Game(title: "Scopa")
        context.insert(game)
        let pa = GameParticipant(player: alice, sortIndex: 0); pa.game = game; context.insert(pa)
        addPoints(11, to: pa, in: context)
        game.closedAt = .now
        try context.save()

        let recorded = game.applyScoreEdit(reason: "Two totals, one competitor",
                                           proposedTotals: [9, 4],
                                           for: game.rankedParticipants,
                                           in: context)
        #expect(recorded == false)
        #expect(game.isEdited == false)
        #expect(pa.totalScore == 11)
    }

    /// The edit-history list shows the most recent correction first.
    @Test func editHistoryIsOrderedNewestFirst() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let game = Game(title: "Scopa")
        game.closedAt = Date(timeIntervalSince1970: 1_000)
        context.insert(game)

        // Inserted out of order on purpose.
        let middle = GameEdit(reason: "Second", editedAt: Date(timeIntervalSince1970: 2_000))
        let oldest = GameEdit(reason: "First", editedAt: Date(timeIntervalSince1970: 1_000))
        let newest = GameEdit(reason: "Third", editedAt: Date(timeIntervalSince1970: 3_000))
        for edit in [middle, oldest, newest] {
            edit.game = game
            context.insert(edit)
        }
        try context.save()

        #expect(game.sortedEdits.map(\.reason) == ["Third", "Second", "First"])
        #expect(game.lastEditedAt == Date(timeIntervalSince1970: 3_000))
    }

    @Test func backupRoundTripIncludesGameEdits() throws {
        let sourceContainer = try makeContainer()
        let source = sourceContainer.mainContext
        let alice = Player(name: "Alice")
        source.insert(alice)
        let game = Game(title: "Scopa")
        game.closedAt = Date(timeIntervalSince1970: 1_500_000_000)
        source.insert(game)
        let pa = GameParticipant(player: alice, sortIndex: 0); pa.game = game; source.insert(pa)
        addPoints(9, to: pa, in: source)

        let firstEditedAt = Date(timeIntervalSince1970: 1_600_000_000)
        let secondEditedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let first = GameEdit(reason: "Miscounted the last scopa", editedAt: firstEditedAt)
        let second = GameEdit(reason: "Forgot the settebello", editedAt: secondEditedAt)
        for edit in [first, second] { edit.game = game; source.insert(edit) }
        try source.save()

        let data = try BackupService.exportData(from: source)
        let destContainer = try makeContainer()
        let dest = destContainer.mainContext
        try BackupService.restore(BackupService.decodeSnapshot(data), into: dest)

        let restored = try #require(try dest.fetch(FetchDescriptor<Game>()).first)
        #expect(restored.isEdited)
        #expect(restored.sortedEdits.map(\.reason) == ["Forgot the settebello", "Miscounted the last scopa"])
        #expect(restored.sortedEdits.map(\.editedAt) == [secondEditedAt, firstEditedAt])
        // The edit log rides along with the game, not instead of its scores.
        #expect(restored.rankedParticipants.map(\.totalScore) == [9])
    }

    /// `edits` is optional precisely so a backup written before closed games could
    /// be edited still restores.
    @Test func gameDecodesFromABackupWrittenWithoutEdits() throws {
        let json = """
        {
          "title": "Scopa",
          "hasTarget": true,
          "targetPoints": 11,
          "createdAt": "2024-06-14T18:45:00Z",
          "closedAt": "2024-06-14T19:30:00Z",
          "participants": []
        }
        """
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let dto = try decoder.decode(BackupSnapshot.GameDTO.self, from: Data(json.utf8))

        #expect(dto.title == "Scopa")
        #expect(dto.targetPoints == 11)
        #expect(dto.edits == nil)   // absent key, not a failure
        // The other back-compat optionals stay nil too, so restore falls back.
        #expect(dto.seats == nil)
        #expect(dto.currentDealerIndex == nil)
        #expect(dto.currentHand == nil)
    }
}
