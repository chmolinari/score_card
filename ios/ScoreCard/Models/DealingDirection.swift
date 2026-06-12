//
//  DealingDirection.swift
//  ScoreCard
//
//  The direction the deal passes around the table after each hand. Stored as an
//  app-wide preference (UserDefaults via @AppStorage); seats are recorded
//  counter-clockwise, so this just decides whether the deal steps +1 or -1.
//

import Foundation

enum DealingDirection: String, CaseIterable, Identifiable {
    case counterClockwise
    case clockwise

    var id: String { rawValue }

    /// Step applied to the current dealer's seat index when the deal advances.
    var step: Int { self == .counterClockwise ? 1 : -1 }

    /// Title-case label for pickers.
    var label: String {
        switch self {
        case .counterClockwise: return "Counter-clockwise"
        case .clockwise: return "Clockwise"
        }
    }

    /// Lowercase adverb for sentences ("the deal passes counter-clockwise").
    var adverb: String {
        switch self {
        case .counterClockwise: return "counter-clockwise"
        case .clockwise: return "clockwise"
        }
    }

    var systemImage: String {
        switch self {
        case .counterClockwise: return "arrow.counterclockwise"
        case .clockwise: return "arrow.clockwise"
        }
    }

    /// Shared UserDefaults key for the app-wide preference.
    static let storageKey = "dealingDirection"
}
