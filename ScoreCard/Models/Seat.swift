//
//  Seat.swift
//  ScoreCard
//
//  One place at the table in a game, occupied by an individual player. Seats are
//  ordered counter-clockwise starting from the first dealer (position 0), so the
//  dealer for each successive hand is just the next seat around.
//
//  Dealers are always individual people — even in a team game, where the
//  competitors are teams, the seats hold the teams' members.
//

import Foundation
import SwiftData

@Model
final class Seat {
    /// 0-based position around the table. Position 0 is the first dealer; play
    /// proceeds counter-clockwise through increasing positions.
    var position: Int = 0
    var player: Player?
    var game: Game?

    init(position: Int) {
        self.position = position
    }

    init(player: Player, position: Int) {
        self.player = player
        self.position = position
    }
}
