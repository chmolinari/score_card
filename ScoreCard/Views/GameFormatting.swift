//
//  GameFormatting.swift
//  ScoreCard
//
//  Small formatting helpers shared across the game screens.
//

import Foundation

enum GameFormatting {
    /// "1 Jun 2026 at 14:30" style stamp for a game's date + time.
    static func dateTime(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .shortened)
    }

    /// Compact duration like "1h 12m" between two dates.
    static func duration(from start: Date, to end: Date) -> String {
        let seconds = max(0, Int(end.timeIntervalSince(start)))
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        if hours > 0 { return "\(hours)h \(minutes)m" }
        if minutes > 0 { return "\(minutes)m" }
        return "less than a minute"
    }
}
