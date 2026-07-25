//
//  GameFormatting.swift
//  ScoreCard
//
//  Small formatting helpers shared across the game screens.
//

import Foundation

enum GameFormatting {
    /// "1 Jun 2026 at 14:30" style stamp for a game's date + time.
    ///
    /// `dateOnly` says whether the stamp's time of day is meaningful. Passing
    /// nil (the default) falls back to inferring it from the stamp sitting at
    /// the start of its local day — correct only for games recorded before
    /// `Game.playedDateOnly` existed, since that inference breaks when the
    /// device changes time zone and mistakes a deliberate midnight for
    /// "no time given".
    static func dateTime(_ date: Date, dateOnly: Bool? = nil) -> String {
        let omitsTime = dateOnly ?? (date == Calendar.current.startOfDay(for: date))
        return omitsTime
            ? date.formatted(date: .abbreviated, time: .omitted)
            : date.formatted(date: .abbreviated, time: .shortened)
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
