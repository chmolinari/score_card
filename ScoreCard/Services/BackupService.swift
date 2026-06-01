//
//  BackupService.swift
//  ScoreCard
//
//  Converts the SwiftData store to/from a portable BackupSnapshot, and erases
//  the whole store. All of this runs on the main actor because it touches the
//  main ModelContext; the (slow, environment-dependent) file I/O lives
//  separately in BackupStorage.
//

import Foundation
import SwiftData

enum BackupError: LocalizedError {
    case unsupportedVersion(Int)
    case notABackup

    var errorDescription: String? {
        switch self {
        case .unsupportedVersion(let v):
            return "This backup was made by a newer version of ScoreCard (format \(v)) and can't be restored."
        case .notABackup:
            return "That file isn't a valid ScoreCard backup."
        }
    }
}

@MainActor
enum BackupService {

    // MARK: Encoding helpers

    private static var encoder: JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return e
    }

    private static var decoder: JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }

    /// Serialize the whole store to backup JSON.
    static func exportData(from context: ModelContext) throws -> Data {
        let snapshot = try makeSnapshot(from: context)
        return try encoder.encode(snapshot)
    }

    /// Decode and validate backup JSON into a snapshot.
    static func decodeSnapshot(_ data: Data) throws -> BackupSnapshot {
        let snapshot: BackupSnapshot
        do {
            snapshot = try decoder.decode(BackupSnapshot.self, from: data)
        } catch {
            throw BackupError.notABackup
        }
        guard snapshot.version <= BackupSnapshot.currentVersion else {
            throw BackupError.unsupportedVersion(snapshot.version)
        }
        return snapshot
    }

    // MARK: Snapshot <-> store

    static func makeSnapshot(from context: ModelContext) throws -> BackupSnapshot {
        let players = try context.fetch(FetchDescriptor<Player>(sortBy: [SortDescriptor(\.createdAt)]))
        let teams = try context.fetch(FetchDescriptor<Team>(sortBy: [SortDescriptor(\.createdAt)]))
        let games = try context.fetch(FetchDescriptor<Game>(sortBy: [SortDescriptor(\.createdAt)]))

        var playerIndex: [PersistentIdentifier: Int] = [:]
        for (i, p) in players.enumerated() { playerIndex[p.persistentModelID] = i }
        var teamIndex: [PersistentIdentifier: Int] = [:]
        for (i, t) in teams.enumerated() { teamIndex[t.persistentModelID] = i }

        var snapshot = BackupSnapshot()
        snapshot.players = players.map { .init(name: $0.name, createdAt: $0.createdAt) }
        snapshot.teams = teams.map { team in
            .init(name: team.name,
                  createdAt: team.createdAt,
                  memberIndices: (team.members ?? []).compactMap { playerIndex[$0.persistentModelID] })
        }
        snapshot.games = games.map { game in
            let participants = (game.participants ?? [])
                .sorted { $0.sortIndex < $1.sortIndex }
                .map { p in
                    BackupSnapshot.ParticipantDTO(
                        nameSnapshot: p.displayName,
                        sortIndex: p.sortIndex,
                        playerIndex: p.player.flatMap { playerIndex[$0.persistentModelID] },
                        teamIndex: p.team.flatMap { teamIndex[$0.persistentModelID] },
                        entries: (p.scoreEntries ?? [])
                            .sorted { $0.timestamp < $1.timestamp }
                            .map { .init(points: $0.points, timestamp: $0.timestamp) }
                    )
                }
            let seats = game.orderedSeats.map { seat in
                BackupSnapshot.SeatDTO(position: seat.position,
                                       playerIndex: seat.player.flatMap { playerIndex[$0.persistentModelID] })
            }
            return .init(title: game.title,
                         hasTarget: game.hasTarget,
                         targetPoints: game.targetPoints,
                         createdAt: game.createdAt,
                         closedAt: game.closedAt,
                         latitude: game.latitude,
                         longitude: game.longitude,
                         locationName: game.locationName,
                         participants: participants,
                         seats: seats,
                         currentDealerIndex: game.currentDealerIndex)
        }
        return snapshot
    }

    /// Decode backup `data` and replace the entire store with its contents.
    /// Returns the snapshot so callers can report what was restored.
    @discardableResult
    static func restore(from data: Data, into context: ModelContext) throws -> BackupSnapshot {
        let snapshot = try decodeSnapshot(data)
        try restore(snapshot, into: context)
        return snapshot
    }

    /// Replace the entire store with the contents of `snapshot`.
    static func restore(_ snapshot: BackupSnapshot, into context: ModelContext) throws {
        try eraseAll(in: context)

        var players: [Player] = []
        for dto in snapshot.players {
            let player = Player(name: dto.name)
            player.createdAt = dto.createdAt
            context.insert(player)
            players.append(player)
        }

        var teams: [Team] = []
        for dto in snapshot.teams {
            let team = Team(name: dto.name)
            team.createdAt = dto.createdAt
            team.members = dto.memberIndices.compactMap { players[safe: $0] }
            context.insert(team)
            teams.append(team)
        }

        for dto in snapshot.games {
            let game = Game(title: dto.title, hasTarget: dto.hasTarget, targetPoints: dto.targetPoints)
            game.createdAt = dto.createdAt
            game.closedAt = dto.closedAt
            game.latitude = dto.latitude
            game.longitude = dto.longitude
            game.locationName = dto.locationName
            context.insert(game)

            for pdto in dto.participants {
                let participant: GameParticipant
                if let i = pdto.playerIndex, let player = players[safe: i] {
                    participant = GameParticipant(player: player, sortIndex: pdto.sortIndex)
                } else if let i = pdto.teamIndex, let team = teams[safe: i] {
                    participant = GameParticipant(team: team, sortIndex: pdto.sortIndex)
                } else {
                    participant = GameParticipant(nameSnapshot: pdto.nameSnapshot, sortIndex: pdto.sortIndex)
                }
                participant.nameSnapshot = pdto.nameSnapshot
                participant.game = game
                context.insert(participant)

                for edto in pdto.entries {
                    let entry = ScoreEntry(points: edto.points, timestamp: edto.timestamp)
                    entry.participant = participant
                    context.insert(entry)
                }
            }

            for sdto in dto.seats ?? [] {
                let seat = Seat(position: sdto.position)
                if let i = sdto.playerIndex { seat.player = players[safe: i] }
                seat.game = game
                context.insert(seat)
            }
            game.currentDealerIndex = dto.currentDealerIndex ?? 0
        }

        try context.save()
    }

    /// Delete every record in the store.
    ///
    /// Objects are deleted individually rather than with the batch
    /// `context.delete(model:)` API: batch deletes clear the store but do NOT
    /// refresh live `@Query` views, so the UI would keep showing stale rows
    /// until relaunch. Per-object deletes notify the queries immediately.
    static func eraseAll(in context: ModelContext) throws {
        // Children first, then parents.
        for entry in try context.fetch(FetchDescriptor<ScoreEntry>()) { context.delete(entry) }
        for participant in try context.fetch(FetchDescriptor<GameParticipant>()) { context.delete(participant) }
        for game in try context.fetch(FetchDescriptor<Game>()) { context.delete(game) }
        for team in try context.fetch(FetchDescriptor<Team>()) { context.delete(team) }
        for player in try context.fetch(FetchDescriptor<Player>()) { context.delete(player) }
        try context.save()
    }
}

private extension Array {
    /// Bounds-checked subscript: nil instead of trapping on a bad index.
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
