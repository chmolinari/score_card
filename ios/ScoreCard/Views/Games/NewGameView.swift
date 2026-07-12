//
//  NewGameView.swift
//  ScoreCard
//
//  Sheet for starting a new game: name it, optionally set a target score, and
//  choose the competitors (any mix of individual players and teams). Players and
//  teams can be created inline here without leaving the screen, and are
//  auto-selected once created. On save the current date/time and (best-effort)
//  geolocation are stamped onto the game.
//

import SwiftUI
import SwiftData
import CoreLocation

struct NewGameView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @Environment(LocationManager.self) private var locationManager

    /// Called with the freshly created game just before the flow dismisses, so
    /// the caller can navigate straight into its scoreboard.
    var onStart: (Game) -> Void = { _ in }

    @AppStorage(DealingDirection.storageKey) private var dealingDirection: DealingDirection = .counterClockwise
    /// Set once the existing games have been mined for their distinct names, so
    /// the one-time seeding never repeats.
    @AppStorage("hasSeededGameNames") private var hasSeededGameNames = false

    /// The chosen game name. Pre-selected to the most recently used one when the
    /// sheet appears; the game's title is copied from it on start.
    @State private var selectedGameName: GameName?
    @State private var hasTarget = false
    @State private var targetPoints = 11

    /// Selected competitors, in the order they were added, so the scoreboard
    /// preserves that order. Stored as model objects (not persistent IDs)
    /// because a freshly created object's ID changes when SwiftData autosaves —
    /// holding the object keeps the selection stable across that change.
    @State private var selectedCompetitors: [GameCompetitor] = []

    @State private var isAddingPlayer = false
    @State private var isAddingTeam = false
    @State private var isAddingGameName = false
    /// Set when the user taps Next; drives navigation to the seating step.
    @State private var draft: GameDraft?

    private var gameTitle: String { selectedGameName?.name ?? "" }
    private var canProceed: Bool { selectedGameName != nil && selectedCompetitors.count >= 2 }

    var body: some View {
        NavigationStack {
            Form {
                GameNameSection(selectedGameName: $selectedGameName) {
                    isAddingGameName = true
                }

                Section {
                    Toggle("Play to a target score", isOn: $hasTarget.animation())
                    if hasTarget {
                        Stepper(value: $targetPoints, in: 1...1000) {
                            HStack {
                                Text("Target")
                                Spacer()
                                Text("\(targetPoints)").foregroundStyle(.secondary)
                            }
                        }
                    }
                } footer: {
                    Text(hasTarget
                         ? "The first to reach \(targetPoints) points wins (e.g. Scopa)."
                         : "Open-ended: just track running totals (e.g. Briscola).")
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

                Section {
                    locationStatusRow
                }
            }
            .navigationTitle("New Game")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Next") {
                        draft = GameDraft(title: gameTitle,
                                          hasTarget: hasTarget,
                                          targetPoints: hasTarget ? targetPoints : nil,
                                          competitors: selectedCompetitors)
                    }
                    .disabled(!canProceed)
                }
            }
            .navigationDestination(item: $draft) { draft in
                SeatingArrangementView(people: draft.people, confirmTitle: "Start Game", direction: dealingDirection) { seating in
                    await startGame(draft: draft, seating: seating)
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
            .onAppear { locationManager.requestAuthorizationIfNeeded() }
            .task {
                guard selectedGameName == nil else { return }
                var seeded = hasSeededGameNames
                selectedGameName = GameNameSection.prepareSelection(in: modelContext, hasSeededGameNames: &seeded)
                hasSeededGameNames = seeded
            }
        }
    }

    @ViewBuilder
    private var locationStatusRow: some View {
        switch locationManager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            Label("Location will be tagged when the game starts.", systemImage: "mappin.and.ellipse")
                .font(.footnote)
                .foregroundStyle(.secondary)
        case .denied, .restricted:
            Label("Location access is off, so this game won't be geo-tagged. Enable it in Settings.", systemImage: "location.slash")
                .font(.footnote)
                .foregroundStyle(.secondary)
        default:
            Label("Location permission will be requested.", systemImage: "location")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Selection

    private func isSelected(_ competitor: GameCompetitor) -> Bool {
        selectedCompetitors.contains(competitor)
    }

    private func toggle(_ competitor: GameCompetitor) {
        if let index = selectedCompetitors.firstIndex(of: competitor) {
            selectedCompetitors.remove(at: index)
        } else {
            // Selecting a team makes this a team game: drop any individual
            // players already chosen so the two never mix.
            if case .team = competitor {
                selectedCompetitors.removeAll { if case .player = $0 { return true } else { return false } }
            }
            selectedCompetitors.append(competitor)
        }
    }

    /// Add a competitor if it isn't already chosen (used for inline creation).
    private func select(_ competitor: GameCompetitor) {
        guard !isSelected(competitor) else { return }
        // Creating a team inline turns this into a team game; clear any players.
        if case .team = competitor {
            selectedCompetitors.removeAll { if case .player = $0 { return true } else { return false } }
        }
        selectedCompetitors.append(competitor)
    }

    /// Create the game, its participants, and the seating decided on the previous
    /// step, then dismiss the whole New Game flow.
    private func startGame(draft: GameDraft, seating: [Player]) async {
        // Best-effort location capture before persisting.
        let location = await locationManager.captureCurrentLocation()

        // Remember this as the most recently used name so it pre-selects next time.
        selectedGameName?.lastUsedAt = .now

        let game = Game(title: draft.title,
                        hasTarget: draft.hasTarget,
                        targetPoints: draft.targetPoints)
        game.apply(location: location)
        modelContext.insert(game)

        for (index, competitor) in draft.competitors.enumerated() {
            let participant: GameParticipant
            switch competitor {
            case .player(let player):
                participant = GameParticipant(player: player, sortIndex: index)
            case .team(let team):
                participant = GameParticipant(team: team, sortIndex: index)
            }
            participant.game = game
            modelContext.insert(participant)
        }

        for (position, player) in seating.enumerated() {
            let seat = Seat(player: player, position: position)
            seat.game = game
            modelContext.insert(seat)
        }
        game.currentDealerIndex = 0   // position 0 is the first dealer

        // Persist now so the game and its participants/seats get their permanent
        // persistentModelIDs before the scoreboard appears. Otherwise the first
        // save during play (e.g. Next Hand) would flip every new object's ID,
        // breaking the scoreboard's view identity (rows keyed by participant ID,
        // and the navigation item) and freezing it.
        try? modelContext.save()

        onStart(game)
        dismiss()
    }
}

#Preview {
    NewGameView()
        .modelContainer(SampleData.container)
        .environment(LocationManager())
}
