//
//  GameEditView.swift
//  ScoreCard
//
//  Sheet for correcting a closed game's final scores. Two steps: first the
//  reason for the edit — mandatory, because a finished result is a record and a
//  change to it has to stay accountable — then the scores themselves. Nothing
//  else about the game is editable here, and an edit never reopens the game.
//

import SwiftUI
import SwiftData

struct GameEditView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @AppStorage(NegativeScores.storageKey) private var allowNegativeScores = false

    let game: Game

    // Competitors and their totals are frozen when the sheet opens. The rows are
    // laid out in ranking order, and re-reading `rankedParticipants` per render
    // would re-sort them under the user's cursor as soon as a total is typed.
    // The snapshot is also the "before" side of every delta and of the
    // did-anything-change check.
    private let participants: [GameParticipant]
    private let originalTotals: [Int]

    @State private var reason = ""
    @State private var proposedTotals: [Int]
    /// What each score field currently shows. Kept alongside `proposedTotals`
    /// rather than derived from it so a half-typed entry stays on screen as the
    /// user typed it.
    @State private var totalTexts: [String]
    /// Set by Continue; drives navigation to the scores step.
    @State private var isEditingScores = false
    /// Latches the commit — see `save()`.
    @State private var isSaving = false
    @FocusState private var focusedScore: Int?

    init(game: Game) {
        self.game = game
        let ranked = game.rankedParticipants
        participants = ranked
        originalTotals = ranked.map(\.totalScore)
        _proposedTotals = State(initialValue: ranked.map(\.totalScore))
        _totalTexts = State(initialValue: ranked.map { String($0.totalScore) })
    }

    private var trimmedReason: String {
        reason.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The motivation cannot be skipped, so there is no path past the first step
    /// without non-whitespace text.
    private var canContinue: Bool { !trimmedReason.isEmpty }

    /// A game counts as edited only when the final score actually differs from
    /// the one it had before — this gates both the Save button and the commit.
    private var hasChanges: Bool {
        GameScoreEdit.isChanged(before: originalTotals, after: proposedTotals)
    }

    var body: some View {
        NavigationStack {
            reasonStep
                .navigationTitle("Reason for Edit")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Continue") { isEditingScores = true }
                            .disabled(!canContinue)
                            .accessibilityIdentifier("editMotivationContinue")
                    }
                }
                .navigationDestination(isPresented: $isEditingScores) { scoresStep }
        }
    }

    // MARK: - Steps

    private var reasonStep: some View {
        Form {
            Section {
                TextField("Why are these scores being changed?", text: $reason, axis: .vertical)
                    .lineLimit(3...6)
                    .accessibilityIdentifier("editMotivationField")
            } header: {
                Text("Reason")
            } footer: {
                Text("Required. It is kept with the game and shown in its edit history.")
            }
        }
    }

    private var scoresStep: some View {
        Form {
            Section {
                ForEach(Array(participants.enumerated()), id: \.offset) { index, participant in
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(participant.displayName)
                                .font(.headline)
                                .lineLimit(1)
                            Text(participant.subtitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 8)
                        TextField("Total", text: totalText(at: index))
                            .keyboardType(.numbersAndPunctuation)   // number pad has no minus key
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 72)
                            .focused($focusedScore, equals: index)
                            .accessibilityIdentifier("editScore\(index)")
                        // The label is hidden but still read out, so it names the
                        // competitor rather than an anonymous "Total".
                        Stepper("\(participant.displayName) total", value: totalValue(at: index))
                            .labelsHidden()
                            .fixedSize()
                    }
                }
            } header: {
                Text("Final Scores")
            } footer: {
                Text(allowNegativeScores
                     ? "Only the scores can be changed."
                     : "Only the scores can be changed. Totals stop at zero.")
            }
        }
        .navigationTitle("Edit Scores")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: focusedScore) { previous, _ in
            // Re-render the field from the stored total once it loses focus, so
            // a clamped or half-typed entry ("-5" with below-zero off, or an
            // emptied field) stops showing a number the game will not store.
            guard let previous, previous < proposedTotals.count else { return }
            totalTexts[previous] = String(proposedTotals[previous])
        }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") { save() }
                    .disabled(!hasChanges || isSaving)
                    .accessibilityIdentifier("editSaveButton")
            }
            // Purely a way to put the keyboard away — the totals are already
            // committed keystroke by keystroke (see `totalText(at:)`).
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { focusedScore = nil }
                    .accessibilityIdentifier("editScoreDone")
            }
        }
    }

    /// Binding for one competitor's total as typed text.
    ///
    /// Parsed on every keystroke rather than when the field gives up focus.
    /// `Save` sits in the navigation bar, and tapping a navigation-bar button
    /// does not resign a text field's focus first, so a commit-on-blur field
    /// would let Save fire against a stale total — silently dropping what was
    /// just typed while still recording an edit claiming the score was corrected.
    private func totalText(at index: Int) -> Binding<String> {
        Binding {
            totalTexts[index]
        } set: { newText in
            totalTexts[index] = newText
            // An empty or half-typed field ("", "-") reads as "unchanged" rather
            // than as zero, so clearing a total never arms Save on its own.
            proposedTotals[index] = GameScoreEdit.normalizedTotal(Int(newText) ?? originalTotals[index],
                                                                 allowNegative: allowNegativeScores)
        }
    }

    /// Binding for the row's stepper, which works on the parsed total directly.
    private func totalValue(at index: Int) -> Binding<Int> {
        Binding {
            proposedTotals[index]
        } set: { newValue in
            let normalized = GameScoreEdit.normalizedTotal(newValue, allowNegative: allowNegativeScores)
            proposedTotals[index] = normalized
            totalTexts[index] = String(normalized)
        }
    }

    // MARK: - Commit

    private func save() {
        // Dismissal is animated and the Save button stays hittable while it
        // plays, so without this latch a second tap would append the same delta
        // again and log a second edit for one correction.
        guard !isSaving else { return }
        isSaving = true

        // The rules (nothing written unless a total moved, one delta entry per
        // changed competitor, `closedAt` untouched) live on the model so the
        // tests drive this exact code — see `Game.applyScoreEdit`.
        game.applyScoreEdit(reason: trimmedReason,
                            proposedTotals: proposedTotals,
                            for: participants,
                            in: modelContext)

        // Saved explicitly because the sheet is torn down immediately after.
        try? modelContext.save()
        dismiss()
    }
}

#Preview {
    if let game = try? SampleData.container.mainContext.fetch(FetchDescriptor<Game>()).first(where: { !$0.isOpen }) {
        GameEditView(game: game)
            .modelContainer(SampleData.container)
    } else {
        Text("No finished game in sample data")
    }
}
