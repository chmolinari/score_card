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
                        }
                        .onDelete(perform: deleteTeams)
                    }
                }
            }
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
        HStack(spacing: 12) {
            Image(systemName: "person.2.circle.fill")
                .font(.title2)
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 3) {
                Text(team.name)
                    .font(.body)
                Text(team.rosterSummary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TallyBadge(tally: team.tally)
            }
            Spacer()
        }
        .contentShape(Rectangle())
    }
}

#Preview {
    TeamsView()
        .modelContainer(SampleData.container)
}
