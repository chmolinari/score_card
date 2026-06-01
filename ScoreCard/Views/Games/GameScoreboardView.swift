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
    @Environment(\.scenePhase) private var scenePhase

    @Bindable var game: Game

    @State private var scoringParticipant: GameParticipant?
    @State private var showCloseConfirmation = false
    @State private var showSeatingSetup = false

    // Shown once when a competitor first reaches the target, inviting the user to
    // end the game. If they decline, the board locks (see `isLockedAtTarget`)
    // until the over-target score is corrected back down.
    @State private var showTargetReachedPrompt = false
    @State private var declinedTargetEnd = false

    // Total score entries the game had at the start of the current hand. "Next
    // Hand" stays disabled until at least one point is scored beyond this, then
    // is reset back to the current total when the hand is advanced — so it's
    // immediately disabled again until the next score lands.
    @State private var handBaselineEntryCount = 0

    @AppStorage(DealingDirection.storageKey) private var dealingDirection: DealingDirection = .counterClockwise

    /// Running count of every competitor's score entries in this game.
    private var totalEntryCount: Int {
        (game.participants ?? []).reduce(0) { $0 + ($1.scoreEntries?.count ?? 0) }
    }

    /// A point must be scored in the current hand before the deal can pass.
    private var canAdvanceHand: Bool { totalEntryCount > handBaselineEntryCount }

    /// Competitors who have hit (or passed) the target score, if one is set.
    private var winners: [GameParticipant] { game.winnersAtTarget }

    /// Comma-separated names of the competitors at the target, for the prompt.
    private var winnerNames: String {
        winners.map(\.displayName).joined(separator: ", ")
    }

    /// True once someone reaches the target. While this holds, the board is
    /// "locked": no points can be added and the deal can't pass — the only move
    /// is to end the game or to reduce an over-target score (to fix a miscount).
    private var isLockedAtTarget: Bool { !winners.isEmpty }

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
                    let overTarget = target.map { entry.score >= $0 } ?? false
                    ScoreboardRow(rank: index + 1,
                                  participant: entry.participant,
                                  total: entry.score,
                                  target: target,
                                  isLocked: isLockedAtTarget,
                                  isOverTarget: overTarget) {
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
        .confirmationDialog("End the game?",
                            isPresented: $showTargetReachedPrompt,
                            titleVisibility: .visible) {
            Button("End Game", role: .destructive) { closeGame() }
            Button("Not Yet", role: .cancel) { declinedTargetEnd = true }
        } message: {
            Text("\(winnerNames) reached the \(game.targetPoints ?? 0)-point target. End the game and record the result? If the score was added by mistake, choose Not Yet and undo the last score to keep playing.")
        }
        .onChange(of: isLockedAtTarget) { _, reached in
            if reached {
                // Only nudge once per time the target is crossed; re-arm for the
                // next crossing once the situation has been resolved.
                if !declinedTargetEnd { showTargetReachedPrompt = true }
            } else {
                declinedTargetEnd = false
            }
        }
        .onAppear {
            // Scoring a hand can be a fast flurry of taps. Turn off autosave
            // while the scoreboard is up so each tap doesn't trigger a CloudKit
            // save; we persist deliberately when the hand advances or we leave.
            modelContext.autosaveEnabled = false
            handBaselineEntryCount = totalEntryCount
        }
        .onDisappear {
            persist()
            modelContext.autosaveEnabled = true
        }
        .onChange(of: scenePhase) { _, phase in
            // Backgrounding doesn't pop the view, so save here too to be safe.
            if phase != .active { persist() }
        }
    }

    private func persist() {
        try? modelContext.save()
    }

    /// Pass the deal to the next player, commit the hand's scores, and re-arm
    /// the baseline so the button disables again until the next point is scored.
    private func advanceHand() {
        withAnimation { game.advanceDealer(dealingDirection) }
        handBaselineEntryCount = totalEntryCount
        persist()
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
                        advanceHand()
                    } label: {
                        Label("Next Hand", systemImage: "arrow.turn.down.right")
                    }
                    .buttonStyle(.bordered)
                    .disabled(!canAdvanceHand || isLockedAtTarget)
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
                if canAdvanceHand {
                    Text("The deal passes \(dealingDirection.adverb). Tap Next Hand when this hand is done.")
                } else {
                    Text("Score this hand to enable passing the deal.")
                }
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
        persist()
    }

    private func targetReachedBanner(ranked: [(participant: GameParticipant, score: Int)], target: Int) -> some View {
        let winners = ranked.filter { $0.score >= target }.map(\.participant.displayName).joined(separator: ", ")
        return Label {
            VStack(alignment: .leading, spacing: 2) {
                Text("Target reached!").font(.headline)
                Text("\(winners) hit \(target) points. End the game to record the result, or undo the last score to fix a mistake and keep playing.")
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
    /// True when someone has reached the target: scoring is frozen game-wide.
    let isLocked: Bool
    /// True when *this* competitor is the one at/over the target.
    let isOverTarget: Bool
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

            controls
                .font(.subheadline)
        }
        .padding(.vertical, 4)
    }

    /// The per-row action area. Normally quick-add buttons; once the target is
    /// reached the whole board is frozen — only the competitor that hit the
    /// target can have a point removed (to correct a misattributed score).
    @ViewBuilder
    private var controls: some View {
        if isLocked {
            if isOverTarget {
                Button(role: .destructive) { undoLastEntry() } label: {
                    Label(undoLabel, systemImage: "arrow.uturn.backward")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(lastEntry == nil)
            } else {
                // No moves allowed for anyone else while the game is at target.
                HStack(spacing: 8) {
                    ForEach([1, 2, 3, 5], id: \.self) { amount in
                        Button("+\(amount)") {}
                            .buttonStyle(.bordered)
                            .frame(maxWidth: .infinity)
                    }
                }
                .disabled(true)
            }
        } else {
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
        }
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

    /// This competitor's most recent scoring action, if any.
    private var lastEntry: ScoreEntry? { participant.sortedEntries.first }

    /// Label that names the action being undone, e.g. "Undo last score (+3)".
    private var undoLabel: String {
        guard let points = lastEntry?.points else { return "Undo last score" }
        return "Undo last score (\(points > 0 ? "+\(points)" : "\(points)"))"
    }

    /// Reverses the competitor's last scoring action by deleting that entry —
    /// the proper fix for a misattributed point, after which play resumes.
    private func undoLastEntry() {
        guard let lastEntry else { return }
        modelContext.delete(lastEntry)
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
