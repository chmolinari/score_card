//
//  TallyBadge.swift
//  ScoreCard
//
//  Compact win/play record shown on player and team rows.
//

import SwiftUI

struct TallyBadge: View {
    let tally: Tally

    var body: some View {
        if tally.isEmpty {
            Text("No games yet")
                .font(.caption)
                .foregroundStyle(.secondary)
        } else {
            HStack(spacing: 10) {
                Label("\(tally.won)", systemImage: "trophy.fill")
                    .foregroundStyle(.yellow)

                Label("\(tally.played)", systemImage: "flag.checkered")
                    .foregroundStyle(.secondary)

                if let pct = tally.winPercentage {
                    Text("\(pct)%")
                        .foregroundStyle(.secondary)
                }

                if tally.inProgress > 0 {
                    Label("\(tally.inProgress)", systemImage: "dot.radiowaves.left.and.right")
                        .foregroundStyle(.green)
                }
            }
            .font(.caption)
            .labelStyle(.titleAndIcon)
            .monospacedDigit()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityText)
        }
    }

    private var accessibilityText: String {
        var parts = ["\(tally.won) won", "\(tally.played) played"]
        if let pct = tally.winPercentage { parts.append("\(pct) percent") }
        if tally.inProgress > 0 { parts.append("\(tally.inProgress) in progress") }
        return parts.joined(separator: ", ")
    }
}

#Preview {
    List {
        TallyBadge(tally: Tally(played: 5, won: 3, inProgress: 1))
        TallyBadge(tally: Tally(played: 0, won: 0, inProgress: 0))
        TallyBadge(tally: Tally(played: 2, won: 0, inProgress: 0))
    }
}
