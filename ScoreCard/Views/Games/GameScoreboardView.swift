//
//  GameScoreboardView.swift
//  ScoreCard
//
//  Live scoreboard for an open game. Add points to each competitor, undo the
//  last entry, watch target progress, and close the game when it's done.
//

import SwiftUI
import SwiftData

struct GameScoreboardView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @Bindable var game: Game

    @State private var scoringParticipant: GameParticipant?
    @State private var showCloseConfirmation = false

    var body: some View {
        List {
            Section {
                GameInfoHeader(game: game)
            }

            if !game.winnersAtTarget.isEmpty {
                Section {
                    targetReachedBanner
                }
            }

            Section("Scores") {
                ForEach(Array(game.rankedParticipants.enumerated()), id: \.element.persistentModelID) { index, participant in
                    ScoreboardRow(rank: index + 1, participant: participant, game: game) {
                        scoringParticipant = participant
                    }
                }
            }
        }
        .navigationTitle(game.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("End Game", role: .destructive) {
                    showCloseConfirmation = true
                }
            }
        }
        .sheet(item: $scoringParticipant) { participant in
            ParticipantScoringSheet(participant: participant)
                .presentationDetents([.medium, .large])
        }
        .confirmationDialog("End this game?",
                            isPresented: $showCloseConfirmation,
                            titleVisibility: .visible) {
            Button("End Game", role: .destructive) { closeGame() }
            Button("Keep Playing", role: .cancel) {}
        } message: {
            Text("The final scores will be saved to your history. You can't add more points after ending.")
        }
    }

    private var targetReachedBanner: some View {
        let winners = game.winnersAtTarget.map(\.displayName).joined(separator: ", ")
        return Label {
            VStack(alignment: .leading, spacing: 2) {
                Text("Target reached!").font(.headline)
                Text("\(winners) hit \(game.targetPoints ?? 0) points. End the game to record the result.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: "flag.checkered.circle.fill")
                .foregroundStyle(.green)
        }
    }

    private func closeGame() {
        game.closedAt = .now
        dismiss()
    }
}

/// A single competitor row on the scoreboard, with inline quick-add buttons.
private struct ScoreboardRow: View {
    @Environment(\.modelContext) private var modelContext
    let rank: Int
    let participant: GameParticipant
    let game: Game
    let onTapMore: () -> Void

    private var reachedTarget: Bool {
        guard game.hasTarget, let target = game.targetPoints else { return false }
        return participant.totalScore >= target
    }

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 12) {
                rankBadge
                VStack(alignment: .leading, spacing: 1) {
                    Text(participant.displayName)
                        .font(.headline)
                    Text(participant.subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text("\(participant.totalScore)")
                    .font(.system(.title, design: .rounded).weight(.bold))
                    .foregroundStyle(reachedTarget ? .green : .primary)
                    .monospacedDigit()
            }

            HStack(spacing: 8) {
                ForEach([1, 2, 3, 5], id: \.self) { amount in
                    Button("+\(amount)") { add(amount) }
                        .buttonStyle(.bordered)
                        .frame(maxWidth: .infinity)
                }
                Button {
                    onTapMore()
                } label: {
                    Image(systemName: "ellipsis")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
            .font(.subheadline)
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private var rankBadge: some View {
        let symbol = rank <= 3 ? "\(rank).circle.fill" : "\(rank).circle"
        Image(systemName: symbol)
            .font(.title2)
            .foregroundStyle(rankColor)
    }

    private var rankColor: Color {
        switch rank {
        case 1: return .yellow
        case 2: return .gray
        case 3: return .brown
        default: return .secondary
        }
    }

    private func add(_ points: Int) {
        let entry = ScoreEntry(points: points)
        entry.participant = participant
        modelContext.insert(entry)
    }
}

#Preview {
    NavigationStack {
        if let game = try? SampleData.container.mainContext.fetch(FetchDescriptor<Game>()).first(where: \.isOpen) {
            GameScoreboardView(game: game)
        } else {
            Text("No open game in sample data")
        }
    }
    .modelContainer(SampleData.container)
}
