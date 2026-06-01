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
    // end the game. If they decline, the board locks (the `reachedTarget` flag
    // derived in `body`) until the over-target score is corrected back down.
    @State private var showTargetReachedPrompt = false
    @State private var declinedTargetEnd = false

    // Total score entries the game had at the start of the current hand. "Next
    // Hand" stays disabled until at least one point is scored beyond this, then
    // is reset back to the current total when the hand is advanced — so it's
    // immediately disabled again until the next score lands.
    @State private var handBaselineEntryCount = 0

    @AppStorage(DealingDirection.storageKey) private var dealingDirection: DealingDirection = .counterClockwise

    /// Running count of every competitor's score entries. Used only off the hot
    /// path (to (re)arm the per-hand baseline), never per row during a render.
    private var totalEntryCount: Int {
        (game.participants ?? []).reduce(0) { $0 + ($1.scoreEntries?.count ?? 0) }
    }

    var body: some View {
        // Everything the rows, banner, and dealer section need is derived ONCE
        // here in a single pass over the competitors. Each competitor's entries
        // are faulted and summed exactly once, then plain Int/Bool values are
        // handed down — so a score tap doesn't re-sum and re-sort per row, which
        // was the source of the lag on older devices.
        let rows = game.participantsInDealingOrder(dealingDirection).map {
            participant -> (participant: GameParticipant, score: Int, entries: Int) in
            let entries = participant.scoreEntries ?? []
            return (participant, entries.reduce(0) { $0 + $1.points }, entries.count)
        }
        let target = game.hasTarget ? game.targetPoints : nil
        let reachedTarget = target.map { t in rows.contains { $0.score >= t } } ?? false
        // A point scored this hand closes the quick-add buttons and arms Next Hand.
        let scoredThisHand = rows.reduce(0) { $0 + $1.entries } > handBaselineEntryCount
        let scoringDisabled = scoredThisHand || reachedTarget
        let winnerNames = target.map { t in
            rows.filter { $0.score >= t }.map(\.participant.displayName).joined(separator: ", ")
        } ?? ""

        return List {
            Section {
                GameInfoHeader(game: game)
            }

            if let target, reachedTarget {
                Section {
                    targetReachedBanner(names: winnerNames, target: target)
                }
            }

            dealerSection(canAdvance: scoredThisHand, reached: reachedTarget)

            Section("Scores") {
                ForEach(Array(rows.enumerated()), id: \.element.participant.persistentModelID) { index, entry in
                    ScoreboardRow(position: index + 1,
                                  participant: entry.participant,
                                  total: entry.score,
                                  target: target,
                                  scoringDisabled: scoringDisabled) {
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
        .onChange(of: reachedTarget) { _, reached in
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
        withAnimation {
            game.advanceDealer(dealingDirection)
            game.currentHand += 1
        }
        handBaselineEntryCount = totalEntryCount
        persist()
    }

    @ViewBuilder
    private func dealerSection(canAdvance: Bool, reached: Bool) -> some View {
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
                    .disabled(!canAdvance || reached)
                }
                Label("Hand \(game.currentHand)", systemImage: "rectangle.stack")
                    .font(.caption)
                    .foregroundStyle(.secondary)
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
                if canAdvance {
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
        game.currentHand = 1
        persist()
    }

    private func targetReachedBanner(names: String, target: Int) -> some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text("Target reached!").font(.headline)
                Text("\(names) hit \(target) points. End the game to record the result, or undo the last score to fix a mistake and keep playing.")
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
    /// 1-based place in the fixed table order (1 = first dealer), not a score rank.
    let position: Int
    let participant: GameParticipant
    /// Precomputed by the parent so the row doesn't re-sum the score entries.
    let total: Int
    /// The game's target score, or nil for an open-ended game.
    let target: Int?
    /// True when the inline quick-add buttons are closed (a point was already
    /// scored this hand, or the target's been reached). The ellipsis stays live.
    let scoringDisabled: Bool
    let onTapMore: () -> Void

    private var reachedTarget: Bool {
        guard let target else { return false }
        return total >= target
    }

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 12) {
                positionBadge
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

    /// The per-row action area. The quick-add buttons close after a point is
    /// scored this hand (reopened by Next Hand) and while the target is reached;
    /// the ellipsis always stays live so the detail sheet — where scores are
    /// corrected — remains reachable.
    @ViewBuilder
    private var controls: some View {
        HStack(spacing: 8) {
            ForEach([1, 2, 3, 5], id: \.self) { amount in
                Button("+\(amount)") { add(amount) }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                    .disabled(scoringDisabled)
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

    /// Neutral table-order badge (the list is in dealing order, not by score).
    private var positionBadge: some View {
        Image(systemName: "\(position).circle")
            .font(.title2)
            .foregroundStyle(.secondary)
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
