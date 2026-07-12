//
//  RegisterGameView.swift
//  ScoreCard
//
//  Sheet for registering a game that was played outside the app: pick the game
//  name and competitors exactly as in New Game, then enter each competitor's
//  final total, when the game was played, and (optionally) where. Saving
//  creates an already-closed game backdated to the played-on date — no seating,
//  target, or geolocation, since a transcription can't know those.
//

import SwiftUI
import SwiftData

struct RegisterGameView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @AppStorage("hasSeededGameNames") private var hasSeededGameNames = false

    @State private var selectedGameName: GameName?
    @State private var selectedCompetitors: [GameCompetitor] = []

    @State private var isAddingPlayer = false
    @State private var isAddingTeam = false
    @State private var isAddingGameName = false
    /// Set when the user taps Next; drives navigation to the details step.
    @State private var draft: GameDraft?

    private var canProceed: Bool { selectedGameName != nil && selectedCompetitors.count >= 2 }

    var body: some View {
        NavigationStack {
            Form {
                GameNameSection(selectedGameName: $selectedGameName) {
                    isAddingGameName = true
                }

                CompetitorSelectionSections(selectedCompetitors: $selectedCompetitors,
                                            onAddPlayer: { isAddingPlayer = true },
                                            onAddTeam: { isAddingTeam = true })

                if !selectedCompetitors.isEmpty {
                    Section("Playing") {
                        ForEach(Array(selectedCompetitors.enumerated()), id: \.offset) { index, competitor in
                            Label("\(index + 1). \(competitor.name)", systemImage: "number.circle")
                        }
                    }
                }
            }
            .navigationTitle("Register Past Game")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Next") {
                        draft = GameDraft(title: selectedGameName?.name ?? "",
                                          hasTarget: false,
                                          targetPoints: nil,
                                          competitors: selectedCompetitors)
                    }
                    .disabled(!canProceed)
                }
            }
            .navigationDestination(item: $draft) { draft in
                RegisterGameDetailsView(draft: draft, gameName: selectedGameName) {
                    dismiss()
                }
            }
            .sheet(isPresented: $isAddingPlayer) {
                PlayerEditView(player: nil) { newPlayer in
                    selectedCompetitors = CompetitorSelectionRules.adding(.player(newPlayer), to: selectedCompetitors)
                }
            }
            .sheet(isPresented: $isAddingTeam) {
                TeamEditView(team: nil) { newTeam in
                    selectedCompetitors = CompetitorSelectionRules.adding(.team(newTeam), to: selectedCompetitors)
                }
            }
            .sheet(isPresented: $isAddingGameName) {
                GameNameEditView(gameName: nil) { newName in
                    selectedGameName = newName
                }
            }
            .task {
                guard selectedGameName == nil else { return }
                var seeded = hasSeededGameNames
                selectedGameName = GameNameSection.prepareSelection(in: modelContext, hasSeededGameNames: &seeded)
                hasSeededGameNames = seeded
            }
        }
    }
}

/// Second step: final scores, played-on date, and optional location for the
/// frozen draft. Scores are index-aligned with the draft's competitors — never
/// keyed by competitor ID, which changes when a fresh object first saves.
private struct RegisterGameDetailsView: View {
    @Environment(\.modelContext) private var modelContext

    let draft: GameDraft
    let gameName: GameName?
    var onSaved: () -> Void

    @State private var scores: [Int?]
    @State private var playedAt = Date.now
    @State private var locationText = ""

    init(draft: GameDraft, gameName: GameName?, onSaved: @escaping () -> Void) {
        self.draft = draft
        self.gameName = gameName
        self.onSaved = onSaved
        _scores = State(initialValue: Array(repeating: nil, count: draft.competitors.count))
    }

    private var canSave: Bool { !scores.contains(nil) }

    var body: some View {
        Form {
            Section {
                ForEach(Array(draft.competitors.enumerated()), id: \.offset) { index, competitor in
                    HStack {
                        Text(competitor.name)
                        Spacer()
                        TextField("Score", value: $scores[index], format: .number)
                            .keyboardType(.numbersAndPunctuation)   // number pad has no minus key
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 100)
                            .accessibilityIdentifier("registerScore\(index)")
                    }
                }
            } header: {
                Text("Final Scores")
            } footer: {
                Text("Enter each competitor's final total.")
            }

            Section {
                DatePicker("Played on",
                           selection: $playedAt,
                           in: ...Date.now,
                           displayedComponents: [.date, .hourAndMinute])
            } footer: {
                Text("The game is filed in History under this date.")
            }

            Section {
                TextField("Location (optional)", text: $locationText)
            }
        }
        .navigationTitle(draft.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save Game") { save() }
                    .disabled(!canSave)
            }
        }
    }

    private func save() {
        let finalScores = zip(draft.competitors, scores).compactMap { competitor, points in
            points.map { GameRegistration.FinalScore(competitor: competitor, points: $0) }
        }
        guard finalScores.count == draft.competitors.count else { return }

        try? GameRegistration.register(title: draft.title,
                                       finalScores: finalScores,
                                       playedAt: playedAt,
                                       locationName: locationText,
                                       in: modelContext)
        // Remember this as the most recently used name so it pre-selects next time.
        gameName?.lastUsedAt = .now
        onSaved()
    }
}

#Preview {
    RegisterGameView()
        .modelContainer(SampleData.container)
}
