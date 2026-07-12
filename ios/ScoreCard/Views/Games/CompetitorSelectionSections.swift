//
//  CompetitorSelectionSections.swift
//  ScoreCard
//
//  The player/team picker sections shared by the New Game and Register Past
//  Game forms: Most Used / All splits, selection rows, and inline "New Player"/
//  "New Team" buttons. The parent owns the selection state and the edit sheets
//  (so inline-created objects can be auto-selected via their onCreate callbacks).
//

import SwiftUI
import SwiftData

/// Selection semantics shared by the picker rows and the inline-create
/// callbacks: a game is between teams OR between individual players — never a
/// mix — so selecting a team drops any players already chosen.
enum CompetitorSelectionRules {

    /// Tap on a row: remove if selected, otherwise add (applying the
    /// team-exclusivity rule).
    static func toggling(_ competitor: GameCompetitor, in list: [GameCompetitor]) -> [GameCompetitor] {
        if let index = list.firstIndex(of: competitor) {
            var result = list
            result.remove(at: index)
            return result
        }
        return adding(competitor, to: list)
    }

    /// Add a competitor if it isn't already chosen (used for inline creation).
    static func adding(_ competitor: GameCompetitor, to list: [GameCompetitor]) -> [GameCompetitor] {
        guard !list.contains(competitor) else { return list }
        var result = list
        if case .team = competitor {
            result.removeAll { if case .player = $0 { return true } else { return false } }
        }
        result.append(competitor)
        return result
    }
}

struct CompetitorSelectionSections: View {
    @Query(sort: \Player.name) private var players: [Player]
    @Query(sort: \Team.name) private var teams: [Team]

    @Binding var selectedCompetitors: [GameCompetitor]
    var onAddPlayer: () -> Void
    var onAddTeam: () -> Void

    var body: some View {
        playersSection
        teamsSection
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
        Button { onAddPlayer() } label: { Label("New Player", systemImage: "plus") }
    }

    private var newTeamButton: some View {
        Button { onAddTeam() } label: { Label("New Team", systemImage: "plus") }
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

    // MARK: - Rows

    private func selectableRow(for competitor: GameCompetitor, subtitle: String? = nil, systemImage: String) -> some View {
        Button {
            selectedCompetitors = CompetitorSelectionRules.toggling(competitor, in: selectedCompetitors)
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
                if selectedCompetitors.contains(competitor) {
                    Image(systemName: "checkmark").foregroundStyle(.tint)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
