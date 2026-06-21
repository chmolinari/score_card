//
//  DrawDealingRule.swift
//  ScoreCard
//
//  Who deals the next hand when a hand ends in a draw (nobody scored that hand).
//  Stored as an app-wide preference (UserDefaults via @AppStorage), shared by
//  key and raw value with the Android app. When set to `.ask`, the scoreboard
//  asks each time a hand is drawn instead of deciding automatically.
//

import Foundation

enum DrawDealingRule: String, CaseIterable, Identifiable {
    /// The last dealer deals the next hand again (the draw doesn't move the deal).
    case redeal
    /// The deal passes to the next dealer, exactly as it does after a scored hand.
    case passOn
    /// Ask the user, each time a hand ends in a draw, who should deal next.
    case ask

    var id: String { rawValue }

    /// Title-case label for pickers.
    var label: String {
        switch self {
        case .redeal: return "Same dealer deals again"
        case .passOn: return "Pass to the next dealer"
        case .ask: return "Ask each time"
        }
    }

    var systemImage: String {
        switch self {
        case .redeal: return "arrow.counterclockwise.circle"
        case .passOn: return "arrow.right.circle"
        case .ask: return "questionmark.circle"
        }
    }

    /// Shared UserDefaults key for the app-wide preference.
    static let storageKey = "drawDealingRule"
}
