//
//  BackupSnapshot.swift
//  ScoreCard
//
//  A portable, Codable representation of the whole database, used for manual
//  iCloud backup/restore. Relationships are encoded as array indices (not
//  SwiftData IDs) so the snapshot is self-contained and survives a full wipe.
//

import Foundation

struct BackupSnapshot: Codable {
    /// Bumped if the format ever changes, so restore can refuse the unknown.
    static let currentVersion = 1

    var version: Int = BackupSnapshot.currentVersion
    var exportedAt: Date = .now
    var players: [PlayerDTO] = []
    var teams: [TeamDTO] = []
    var games: [GameDTO] = []

    struct PlayerDTO: Codable {
        var name: String
        var createdAt: Date
    }

    struct TeamDTO: Codable {
        var name: String
        var createdAt: Date
        /// Indices into `players`.
        var memberIndices: [Int]
    }

    struct GameDTO: Codable {
        var title: String
        var hasTarget: Bool
        var targetPoints: Int?
        var createdAt: Date
        var closedAt: Date?
        var latitude: Double?
        var longitude: Double?
        var locationName: String?
        var participants: [ParticipantDTO]
        // Optional so backups written before seating was added still decode.
        var seats: [SeatDTO]?
        var currentDealerIndex: Int?
    }

    struct SeatDTO: Codable {
        var position: Int
        /// Index into `players`, or nil if the seated player was deleted.
        var playerIndex: Int?
    }

    struct ParticipantDTO: Codable {
        var nameSnapshot: String
        var sortIndex: Int
        /// Index into `players` if this competitor is a single player.
        var playerIndex: Int?
        /// Index into `teams` if this competitor is a team.
        var teamIndex: Int?
        var entries: [EntryDTO]
    }

    struct EntryDTO: Codable {
        var points: Int
        var timestamp: Date
    }
}
