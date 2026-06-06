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
        }
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

    private func deleteTeams(at offsets: IndexSet) {
        // Offsets index into the displayed (sorted) list, not the raw query.
        for index in offsets {
            modelContext.delete(sortedTeams[index])
        }
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
