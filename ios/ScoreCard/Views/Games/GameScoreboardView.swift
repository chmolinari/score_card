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

    // Each competitor's total score at the start of the current hand, keyed by
    // identity. "Next Hand" stays disabled until some competitor's score differs
    // from this baseline — i.e. the hand actually changed the score, not merely
    // added entries that cancel back out. Re-snapshotted when the hand advances,
    // so the button disables again until the next net change. A hand that leaves
    // every score where it started can only be resolved as a draw.
    @State private var handBaselineScores: [PersistentIdentifier: Int] = [:]

    // Bumped on every manual score change. SwiftData does NOT reliably fire
    // SwiftUI observation for a to-many relationship (`scoreEntries`) that's
    // mutated via its inverse (`entry.participant = …`) once the context has
    // been saved — which first happens when "Next Hand" persists the hand. So
    // after the first hand the board would read correct model values but never
    // be told to redraw. Touching this @State on each add invalidates the body
    // deterministically, independent of relationship observation.
    @State private var scoreRevision = 0

    @AppStorage(DealingDirection.storageKey) private var dealingDirection: DealingDirection = .counterClockwise
    @AppStorage(DrawDealingRule.storageKey) private var drawDealingRule: DrawDealingRule = .ask

    // Shown when a hand is declared a draw and the user's preference is to be
    // asked who deals next (the `.ask` rule).
    @State private var showDrawDealerPrompt = false

    /// Snapshot of every competitor's current total score, keyed by identity.
    /// Used only off the hot path to (re)arm the per-hand baseline, never per row
    /// during a render.
    private func currentScores() -> [PersistentIdentifier: Int] {
        var scores: [PersistentIdentifier: Int] = [:]
        for participant in game.participants ?? [] {
            scores[participant.persistentModelID] = (participant.scoreEntries ?? []).reduce(0) { $0 + $1.points }
        }
        return scores
    }

    var body: some View {
        // Establishes the per-render dependency on manual score mutations (see
        // `scoreRevision`). Changing @State always invalidates this view, so the
        // single-pass derivation below re-reads the live (possibly unsaved)
        // totals whenever a point is added.
        _ = scoreRevision

        // Everything the rows, banner, and dealer section need is derived ONCE
        // here in a single pass over the competitors. Each competitor's entries
        // are faulted and summed exactly once, then plain Int/Bool values are
        // handed down — so a score tap doesn't re-sum and re-sort per row, which
        // was the source of the lag on older devices.
        let rows = game.participantsInDealingOrder(dealingDirection).map {
            participant -> (participant: GameParticipant, score: Int) in
            let entries = participant.scoreEntries ?? []
            return (participant, entries.reduce(0) { $0 + $1.points })
        }
        let target = game.hasTarget ? game.targetPoints : nil
        let reachedTarget = target.map { t in rows.contains { $0.score >= t } } ?? false
        // A net score change this hand closes the quick-add buttons and arms Next
        // Hand. Comparing against the per-hand baseline (not the entry count) means
        // adding then undoing points back to where the hand started leaves it a
        // draw: Next Hand re-disables and only "Hand Was a Draw" stays available.
        let scoredThisHand = rows.contains { $0.score != (handBaselineScores[$0.participant.persistentModelID] ?? 0) }
        let scoringDisabled = scoredThisHand || reachedTarget
        let winnerNames = target.map { t in
            rows.filter { $0.score >= t }.map(\.participant.displayName).joined(separator: ", ")
        } ?? ""

        return List {
            if let target, reachedTarget {
                Section {
                    targetReachedBanner(names: winnerNames, target: target)
                        .cardRow()
                }
            }

            Section {
                dealerCard(canAdvance: scoredThisHand, reached: reachedTarget)
                    .cardRow()
            } header: {
                PlayfulSectionHeader(title: "Current Hand", systemImage: "hand.draw.fill")
            }

            Section {
                ForEach(Array(rows.enumerated()), id: \.element.participant.persistentModelID) { index, entry in
                    ScoreboardRow(position: index + 1,
                                  participant: entry.participant,
                                  total: entry.score,
                                  target: target,
                                  scoringDisabled: scoringDisabled,
                                  onScore: { scoreRevision += 1 }) {
                        scoringParticipant = entry.participant
                    }
                    .cardRow()
                }
            } header: {
                PlayfulSectionHeader(title: "Scores", systemImage: "list.number")
            }

            Section {
                GameInfoHeader(game: game)
                    .cardTile()
                    .cardRow()
            } header: {
                PlayfulSectionHeader(title: "Details", systemImage: "info.circle.fill")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(AppBackground())
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
        .confirmationDialog("Hand was a draw",
                            isPresented: $showDrawDealerPrompt,
                            titleVisibility: .visible) {
            Button("\(game.currentDealer?.name ?? "Same dealer") deals again") {
                advanceHand(passDeal: false)
            }
            Button("Pass to \(game.nextDealer(dealingDirection)?.name ?? "next player")") {
                advanceHand(passDeal: true)
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("No one won this hand. Who deals the next one?")
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
            handBaselineScores = currentScores()
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

    /// Start the next hand, commit the current hand's scores, and re-arm the
    /// baseline so the button disables again until the next point is scored.
    /// `passDeal` moves the deal on to the next dealer; a drawn hand may keep it
    /// with the same dealer depending on the `drawDealingRule` preference.
    private func advanceHand(passDeal: Bool = true) {
        withAnimation {
            if passDeal { game.advanceDealer(dealingDirection) }
            game.currentHand += 1
        }
        handBaselineScores = currentScores()
        persist()
    }

    /// Resolve who deals after a drawn hand per the user's preference: redeal
    /// with the same dealer, pass the deal on, or ask each time.
    private func handleDrawnHand() {
        switch drawDealingRule {
        case .redeal: advanceHand(passDeal: false)
        case .passOn: advanceHand(passDeal: true)
        case .ask: showDrawDealerPrompt = true
        }
    }

    @ViewBuilder
    private func dealerCard(canAdvance: Bool, reached: Bool) -> some View {
        if let dealer = game.currentDealer {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    Avatar(name: dealer.name, systemImage: "hand.draw.fill", size: 44)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Dealer this hand")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(dealer.name)
                            .font(.title3.weight(.bold))
                    }
                    Spacer()
                    VStack(spacing: 2) {
                        Text("HAND")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.secondary)
                        Text("\(game.currentHand)")
                            .font(.system(.title2, design: .rounded).weight(.bold))
                            .foregroundStyle(Theme.accent)
                            .monospacedDigit()
                            .contentTransition(.numericText())
                    }
                }

                Button {
                    advanceHand()
                } label: {
                    Label("Next Hand", systemImage: "arrow.turn.down.right")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(Theme.accent)
                .disabled(!canAdvance || reached)

                // A drawn hand scores nothing, so "Next Hand" stays disabled.
                // This starts the next hand anyway — only meaningful before any
                // point is scored this hand (otherwise it wasn't a draw). Who
                // deals next follows the `drawDealingRule` preference.
                Button {
                    handleDrawnHand()
                } label: {
                    Label("Hand Was a Draw", systemImage: "equal.circle")
                        .font(.subheadline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .tint(.secondary)
                .disabled(canAdvance || reached)

                if let next = game.nextDealer(dealingDirection) {
                    Label("Next to deal: \(next.name)", systemImage: "arrow.right")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text(canAdvance
                     ? "The deal passes \(dealingDirection.adverb). Tap Next Hand when this hand is done."
                     : "Score this hand to pass the deal, or mark it a draw if no one scored.")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            .cardTile()
        } else {
            Button {
                showSeatingSetup = true
            } label: {
                Label("Set Up Seating & Dealer", systemImage: "person.3.sequence.fill")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .tint(Theme.accent)
            .cardTile()
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
        HStack(spacing: 12) {
            Image(systemName: "flag.checkered.circle.fill")
                .font(.largeTitle)
                .foregroundStyle(.white)
            VStack(alignment: .leading, spacing: 2) {
                Text("Target reached!")
                    .font(.headline)
                    .foregroundStyle(.white)
                Text("\(names) hit \(target) points. End the game to record the result, or undo the last score to fix a mistake and keep playing.")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.9))
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(colors: [Theme.amber, Theme.coral],
                           startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: 22, style: .continuous)
        )
        .shadow(color: Theme.coral.opacity(0.35), radius: 10, x: 0, y: 5)
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
    /// Called after a point is added so the parent can invalidate its derived
    /// totals (relationship-inverse mutations aren't reliably observed — see
    /// `GameScoreboardView.scoreRevision`).
    let onScore: () -> Void
    let onTapMore: () -> Void

    private var reachedTarget: Bool {
        guard let target else { return false }
        return total >= target
    }

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Avatar(name: participant.displayName,
                       systemImage: participant.isTeam ? "person.2.fill" : nil,
                       size: 46)
                    .overlay(alignment: .bottomTrailing) { positionBadge }
                VStack(alignment: .leading, spacing: 1) {
                    Text(participant.displayName)
                        .font(.headline)
                    Text(participant.subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text("\(total)")
                    .font(.system(size: 40, weight: .heavy, design: .rounded))
                    .foregroundStyle(reachedTarget ? Theme.amber : Color.primary)
                    .monospacedDigit()
                    .contentTransition(.numericText(value: Double(total)))
                    .animation(.snappy, value: total)
            }

            controls
                .font(.subheadline)
        }
        .cardTile()
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
                    .tint(Theme.teal)
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
            .tint(.secondary)
        }
    }

    /// Neutral table-order badge (the list is in dealing order, not by score),
    /// tucked onto the avatar.
    private var positionBadge: some View {
        Text("\(position)")
            .font(.caption2.weight(.bold))
            .foregroundStyle(.white)
            .frame(width: 18, height: 18)
            .background(Theme.plum, in: Circle())
            .overlay(Circle().stroke(Theme.cardSurface, lineWidth: 2))
    }

    private func add(_ points: Int) {
        let entry = ScoreEntry(points: points)
        entry.participant = participant
        modelContext.insert(entry)
        onScore()
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
