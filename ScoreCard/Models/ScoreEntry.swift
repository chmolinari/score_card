//
//  ScoreEntry.swift
//  ScoreCard
//
//  A single scoring event for one participant (e.g. "+3"). Storing each addition
//  individually gives an exact undo and a full per-game scoring log.
//

import Foundation
import SwiftData

@Model
final class ScoreEntry {
    var points: Int = 0
    var timestamp: Date = Date.now
    var participant: GameParticipant?

    init(points: Int, timestamp: Date = .now) {
        self.points = points
        self.timestamp = timestamp
    }
}
