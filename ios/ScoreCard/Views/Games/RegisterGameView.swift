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
    @AppStorage(NegativeScores.storageKey) private var allowNegativeScores = false

    let draft: GameDraft
    let gameName: GameName?
    var onSaved: () -> Void

    // Backed by text and parsed per keystroke rather than by
    // TextField(value:format:), which only writes its binding when the field
    // gives up focus. "Save Game" is a navigation-bar button and tapping one
    // does not resign a text field's focus first, so a commit-on-blur field
    // leaves the button inert on the last score typed — and, once every field
    // has committed once, saves a stale total for a score the user went back
    // and corrected.
    @State private var scoreTexts: [String]
    /// Latches the commit — see `save()`.
    @State private var isSaving = false
    @State private var hasDate = true
    @State private var hasTime = false
    @State private var playedAt = Date.now
    @State private var locationText = ""

    init(draft: GameDraft, gameName: GameName?, onSaved: @escaping () -> Void) {
        self.draft = draft
        self.gameName = gameName
        self.onSaved = onSaved
        _scoreTexts = State(initialValue: Array(repeating: "", count: draft.competitors.count))
    }

    /// Each competitor's typed total, or nil while the field is empty or
    /// half-typed ("-"). An unparseable or out-of-range entry stays nil rather
    /// than trapping, so a huge paste simply leaves Save disabled.
    private var scores: [Int?] { scoreTexts.map { Int($0) } }

    private var canSave: Bool { !isSaving && !scores.contains(nil) }

    var body: some View {
        Form {
            Section {
                ForEach(Array(draft.competitors.enumerated()), id: \.offset) { index, competitor in
                    HStack {
                        Text(competitor.name)
                        Spacer()
                        TextField("Score", text: $scoreTexts[index])
                            // The minus key only exists when below-zero totals
                            // are allowed; the plain number pad has none.
                            .keyboardType(allowNegativeScores ? .numbersAndPunctuation : .numberPad)
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 100)
                            .accessibilityIdentifier("registerScore\(index)")
                    }
                }
            } header: {
                Text("Final Scores")
            } footer: {
                Text(allowNegativeScores
                     ? "Enter each competitor's final total."
                     : "Enter each competitor's final total. Totals stop at zero.")
            }

            Section {
                Toggle("Set the date", isOn: $hasDate.animation())
                if hasDate {
                    DatePicker("Played on",
                               selection: $playedAt,
                               in: ...Date.now,
                               displayedComponents: hasTime ? [.date, .hourAndMinute] : [.date])
                    Toggle("Set the time", isOn: $hasTime.animation())
                }
            } header: {
                Text("Played On")
            } footer: {
                if !hasDate {
                    Text("Without a date, the game is filed in History under today.")
                } else if !hasTime {
                    Text("The game is filed in History under this date, without a time of day.")
                } else {
                    Text("The game is filed in History under this date and time.")
                }
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
        // Dismissal is animated and the button stays hittable while it plays,
        // so without this a second tap would register the game twice.
        guard !isSaving else { return }
        isSaving = true

        let finalScores = zip(draft.competitors, scores).compactMap { competitor, points in
            points.map { GameRegistration.FinalScore(competitor: competitor, points: $0) }
        }
        guard finalScores.count == draft.competitors.count else {
            isSaving = false
            return
        }

        try? GameRegistration.register(title: draft.title,
                                       finalScores: finalScores,
                                       playedAt: GameRegistration.playedDate(hasDate: hasDate,
                                                                             hasTime: hasTime,
                                                                             selection: playedAt),
                                       locationName: locationText,
                                       // A date with no time is the only case
                                       // whose time of day is unknown; with no
                                       // date at all the stamp is "now".
                                       playedDateOnly: hasDate && !hasTime,
                                       allowNegativeScores: allowNegativeScores,
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
