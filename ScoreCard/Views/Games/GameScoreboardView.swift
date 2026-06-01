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
    @State private var showSeatingSetup = false

    @AppStorage(DealingDirection.storageKey) private var dealingDirection: DealingDirection = .counterClockwise

    var body: some View {
        // Compute every competitor's total once per render (summing each one's
        // score entries is O(entries)); the banner and the rows below all reuse
        // these instead of re-summing and re-sorting on each access.
        let ranked = game.rankedScores
        let target = game.hasTarget ? game.targetPoints : nil

        return List {
            Section {
                GameInfoHeader(game: game)
            }

            if let target, ranked.contains(where: { $0.score >= target }) {
                Section {
                    targetReachedBanner(ranked: ranked, target: target)
                }
            }

            dealerSection

            Section("Scores") {
                ForEach(Array(ranked.enumerated()), id: \.element.participant.persistentModelID) { index, entry in
                    ScoreboardRow(rank: index + 1, participant: entry.participant, total: entry.score, target: target) {
                        scoringParticipant = entry.participant
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
        .sheet(isPresented: $showSeatingSetup) {
            NavigationStack {
                SeatingArrangementView(people: peopleForSeating, confirmTitle: "Save", direction: dealingDirection) { ordered in
                    applySeating(ordered)
                    showSeatingSetup = false
                }
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { showSeatingSetup = false }
                    }
                }
            }
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

    @ViewBuilder
    private var dealerSection: some View {
        Section {
            if let dealer = game.currentDealer {
                HStack(spacing: 12) {
                    Image(systemName: "hand.draw.fill")
                        .font(.title3)
                        .foregroundStyle(.tint)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Dealer this hand")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(dealer.name)
                            .font(.headline)
                    }
                    Spacer()
                    Button {
                        withAnimation { game.advanceDealer(dealingDirection) }
                    } label: {
                        Label("Next Hand", systemImage: "arrow.turn.down.right")
                    }
                    .buttonStyle(.bordered)
                }
                if let next = game.nextDealer(dealingDirection) {
                    Text("Next to deal: \(next.name)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } else {
                Button {
                    showSeatingSetup = true
                } label: {
                    Label("Set Up Seating & Dealer", systemImage: "person.3.sequence.fill")
                }
            }
        } header: {
            Text("Current Hand")
        } footer: {
            if game.currentDealer != nil {
                Text("The deal passes \(dealingDirection.adverb). Tap Next Hand when a new hand begins.")
            }
        }
    }

    /// Individual people at the table, derived from the competitors (teams
    /// expanded to members), for setting up seating on an existing game.
    private var peopleForSeating: [Player] {
        var seen = Set<PersistentIdentifier>()
        var result: [Player] = []
        func add(_ player: Player) {
            if seen.insert(player.persistentModelID).inserted { result.append(player) }
        }
        for participant in game.participants ?? [] {
            if let player = participant.player {
                add(player)
            } else if let team = participant.team {
                team.sortedMembers.forEach(add)
            }
        }
        return result
    }

    private func applySeating(_ ordered: [Player]) {
        for seat in game.seats ?? [] { modelContext.delete(seat) }
        for (position, player) in ordered.enumerated() {
            let seat = Seat(player: player, position: position)
            seat.game = game
            modelContext.insert(seat)
        }
        game.currentDealerIndex = 0
    }

    private func targetReachedBanner(ranked: [(participant: GameParticipant, score: Int)], target: Int) -> some View {
        let winners = ranked.filter { $0.score >= target }.map(\.participant.displayName).joined(separator: ", ")
        return Label {
            VStack(alignment: .leading, spacing: 2) {
                Text("Target reached!").font(.headline)
                Text("\(winners) hit \(target) points. End the game to record the result.")
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
    /// Precomputed by the parent so the row doesn't re-sum the score entries.
    let total: Int
    /// The game's target score, or nil for an open-ended game.
    let target: Int?
    let onTapMore: () -> Void

    private var reachedTarget: Bool {
        guard let target else { return false }
        return total >= target
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
                Text("\(total)")
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
