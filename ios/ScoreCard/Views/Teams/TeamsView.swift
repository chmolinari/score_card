//
//  TeamsView.swift
//  ScoreCard
//
//  The Teams tab: browse, add, edit, and delete teams (groups of players).
//

import SwiftUI
import SwiftData

struct TeamsView: View {
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \Team.name) private var teams: [Team]
    @AppStorage(CompetitorSortOrder.teamsStorageKey) private var sortOrder: CompetitorSortOrder = .nameAscending

    @State private var editingTeam: Team?
    @State private var isAddingTeam = false
    /// Teams a swipe has proposed deleting, held until the user confirms.
    /// Resolved to objects up front — re-reading `sortedTeams` by index while
    /// deleting would walk a list that is shrinking underneath it.
    @State private var pendingDeletion: [Team] = []

    /// Teams in the user's chosen order. "Score" sorts can't live in the
    /// `@Query` because the tally is computed on the fly, so we re-sort here.
    private var sortedTeams: [Team] {
        CompetitorSorter.sorted(teams, by: sortOrder, name: \.name, tally: \.tally)
    }

    var body: some View {
        NavigationStack {
            Group {
                if teams.isEmpty {
                    ContentUnavailableView {
                        Label("No Teams", systemImage: "person.2.badge.plus")
                    } description: {
                        Text("Group players into teams to score them together. Players can still play on their own.")
                    } actions: {
                        Button("Add Team") { isAddingTeam = true }
                            .buttonStyle(.borderedProminent)
                    }
                } else {
                    List {
                        ForEach(sortedTeams) { team in
                            Button {
                                editingTeam = team
                            } label: {
                                TeamRow(team: team)
                            }
                            .buttonStyle(.plain)
                            .cardRow()
                        }
                        .onDelete(perform: deleteTeams)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
            .background(AppBackground())
            .navigationTitle("Teams")
            .toolbar {
                if !teams.isEmpty {
                    ToolbarItem(placement: .topBarLeading) { EditButton() }
                    ToolbarItem(placement: .topBarTrailing) { sortMenu }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isAddingTeam = true
                    } label: {
                        Label("Add Team", systemImage: "plus")
                    }
                }
            }
            .sheet(isPresented: $isAddingTeam) {
                TeamEditView(team: nil)
            }
            .sheet(item: $editingTeam) { team in
                TeamEditView(team: team)
            }
            .confirmationDialog(deletionTitle,
                                isPresented: showingDeleteConfirmation,
                                titleVisibility: .visible) {
                Button("Delete Team", role: .destructive) { commitDeletion() }
                Button("Cancel", role: .cancel) { pendingDeletion = [] }
            } message: {
                Text(deletionMessage)
            }
        }
    }

    // MARK: - Deletion

    private var showingDeleteConfirmation: Binding<Bool> {
        Binding(get: { !pendingDeletion.isEmpty },
                set: { if !$0 { pendingDeletion = [] } })
    }

    private var deletionTitle: String {
        guard let team = pendingDeletion.first else { return "" }
        return pendingDeletion.count == 1 ? "Delete \(team.name)?" : "Delete \(pendingDeletion.count) teams?"
    }

    /// Makes the blast radius explicit: unlike deleting a player, this removes
    /// only the grouping — the people and the past results both survive.
    private var deletionMessage: String {
        let bodies = pendingDeletion.map {
            RosterCheck.teamDeletionMessage(teamName: $0.name, memberCount: $0.sortedMembers.count)
        }
        return bodies.joined(separator: " ")
            + " Because data syncs to iCloud, this also removes them from your other devices. This can't be undone."
    }

    private func commitDeletion() {
        for team in pendingDeletion { modelContext.delete(team) }
        pendingDeletion = []
    }

    /// A menu to pick how the list is ordered; the choice is remembered.
    private var sortMenu: some View {
        Menu {
            Picker("Sort By", selection: $sortOrder) {
                ForEach(CompetitorSortOrder.allCases) { order in
                    Label(order.label, systemImage: order.systemImage).tag(order)
                }
            }
        } label: {
            Label("Sort", systemImage: "arrow.up.arrow.down")
        }
        // Announce the active order to VoiceOver ("Sort, Name (A–Z)").
        .accessibilityValue(sortOrder.label)
    }

    /// Swiping proposes a deletion rather than performing it; the confirmation
    /// commits. Offsets index into the displayed (sorted) list, not the raw
    /// query, so they are resolved to objects here while the list is still whole.
    private func deleteTeams(at offsets: IndexSet) {
        let sorted = sortedTeams
        pendingDeletion = offsets.compactMap { sorted.indices.contains($0) ? sorted[$0] : nil }
    }
}

private struct TeamRow: View {
    let team: Team

    var body: some View {
        HStack(spacing: 14) {
            Avatar(name: team.name, systemImage: "person.2.fill", size: 46)
            VStack(alignment: .leading, spacing: 4) {
                Text(team.name)
                    .font(.headline)
                Text(team.rosterSummary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                TallyBadge(tally: team.tally)
            }
            Spacer()
        }
        .cardTile()
        .contentShape(Rectangle())
    }
}

#Preview {
    TeamsView()
        .modelContainer(SampleData.container)
}
