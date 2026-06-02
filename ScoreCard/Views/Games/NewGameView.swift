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

    @Query(sort: \Player.name) private var players: [Player]
    @Query(sort: \Team.name) private var teams: [Team]

    @AppStorage(DealingDirection.storageKey) private var dealingDirection: DealingDirection = .counterClockwise

    @State private var title: String = ""
    @State private var hasTarget = false
    @State private var targetPoints = 11

    /// Selected competitors, in the order they were added, so the scoreboard
    /// preserves that order. Stored as model objects (not persistent IDs)
    /// because a freshly created object's ID changes when SwiftData autosaves —
    /// holding the object keeps the selection stable across that change.
    @State private var selectedCompetitors: [GameCompetitor] = []

    @State private var isAddingPlayer = false
    @State private var isAddingTeam = false
    /// Set when the user taps Next; drives navigation to the seating step.
    @State private var draft: GameDraft?

    private var trimmedTitle: String { title.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canProceed: Bool { !trimmedTitle.isEmpty && selectedCompetitors.count >= 2 }

    var body: some View {
        NavigationStack {
            Form {
                Section("Game") {
                    TextField("Game name (e.g. Scopa, Briscola)", text: $title)
                        .textInputAutocapitalization(.words)
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

                playersSection
                teamsSection

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
                        draft = GameDraft(title: trimmedTitle,
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
                    select(.player(newPlayer))
                }
            }
            .sheet(isPresented: $isAddingTeam) {
                TeamEditView(team: nil) { newTeam in
                    select(.team(newTeam))
                }
            }
            .onAppear { locationManager.requestAuthorizationIfNeeded() }
        }
    }

    /// True once any team is selected. A game is between teams OR between
    /// individual players — never a mix — so selecting a team turns this into a
    /// team game and the players list is hidden.
    private var isTeamGame: Bool {
        selectedCompetitors.contains { if case .team = $0 { return true } else { return false } }
    }

    @ViewBuilder
    private var playersSection: some View {
        if isTeamGame {
            // Hidden: this is a team game, so individual players don't apply.
            EmptyView()
        } else if showsMostUsedPlayers {
            Section("Most Used Players") {
                ForEach(frequentPlayers) { player in
                    selectableRow(for: .player(player), systemImage: "person.circle.fill")
                }
            }
            Section("All Players") {
                ForEach(otherPlayers) { player in
                    selectableRow(for: .player(player), systemImage: "person.circle.fill")
                }
                newPlayerButton
            }
        } else {
            Section("Players") {
                ForEach(players) { player in
                    selectableRow(for: .player(player), systemImage: "person.circle.fill")
                }
                newPlayerButton
            }
        }
    }

    @ViewBuilder
    private var teamsSection: some View {
        if showsMostUsedTeams {
            Section("Most Used Teams") {
                ForEach(frequentTeams) { team in
                    selectableRow(for: .team(team), subtitle: team.rosterSummary, systemImage: "person.2.circle.fill")
                }
            }
            Section("All Teams") {
                ForEach(otherTeams) { team in
                    selectableRow(for: .team(team), subtitle: team.rosterSummary, systemImage: "person.2.circle.fill")
                }
                newTeamButton
            }
        } else {
            Section("Teams") {
                ForEach(teams) { team in
                    selectableRow(for: .team(team), subtitle: team.rosterSummary, systemImage: "person.2.circle.fill")
                }
                newTeamButton
            }
        }
    }

    private var newPlayerButton: some View {
        Button { isAddingPlayer = true } label: { Label("New Player", systemImage: "plus") }
    }

    private var newTeamButton: some View {
        Button { isAddingTeam = true } label: { Label("New Team", systemImage: "plus") }
    }

    // MARK: - Most-used ranking

    /// Top players by number of games played; shown above the full list.
    private var frequentPlayers: [Player] {
        FrequentPicker.top(players, usage: { $0.usageCount }, name: { $0.name })
    }

    /// Players not in the "most used" set, kept in the @Query's alphabetical order.
    private var otherPlayers: [Player] {
        let ids = Set(frequentPlayers.map(\.persistentModelID))
        return players.filter { !ids.contains($0.persistentModelID) }
    }

    /// Only split into Most Used + All when it actually helps (there are extras).
    private var showsMostUsedPlayers: Bool {
        !frequentPlayers.isEmpty && !otherPlayers.isEmpty
    }

    private var frequentTeams: [Team] {
        FrequentPicker.top(teams, usage: { $0.usageCount }, name: { $0.name })
    }

    private var otherTeams: [Team] {
        let ids = Set(frequentTeams.map(\.persistentModelID))
        return teams.filter { !ids.contains($0.persistentModelID) }
    }

    private var showsMostUsedTeams: Bool {
        !frequentTeams.isEmpty && !otherTeams.isEmpty
    }

    private func selectableRow(for competitor: GameCompetitor, subtitle: String? = nil, systemImage: String) -> some View {
        Button {
            toggle(competitor)
        } label: {
            HStack {
                Image(systemName: systemImage).foregroundStyle(.tint)
                VStack(alignment: .leading, spacing: 1) {
                    Text(competitor.name).foregroundStyle(.primary)
                    if let subtitle {
                        Text(subtitle).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if isSelected(competitor) {
                    Image(systemName: "checkmark").foregroundStyle(.tint)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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
