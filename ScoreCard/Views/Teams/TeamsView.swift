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

    @State private var editingTeam: Team?
    @State private var isAddingTeam = false

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
                        ForEach(teams) { team in
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
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isAddingTeam = true
                    } label: {
                        Label("Add Team", systemImage: "plus")
                    }
                }
                if !teams.isEmpty {
                    ToolbarItem(placement: .topBarLeading) { EditButton() }
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

    private func deleteTeams(at offsets: IndexSet) {
        for index in offsets {
            modelContext.delete(teams[index])
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
