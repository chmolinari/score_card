//
//  PlayersView.swift
//  ScoreCard
//
//  The Players tab: browse, add, edit, and delete the roster of people.
//

import SwiftUI
import SwiftData

struct PlayersView: View {
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \Player.name) private var players: [Player]

    @State private var editingPlayer: Player?
    @State private var isAddingPlayer = false

    var body: some View {
        NavigationStack {
            Group {
                if players.isEmpty {
                    ContentUnavailableView {
                        Label("No Players", systemImage: "person.crop.circle.badge.plus")
                    } description: {
                        Text("Add the people who will be keeping score.")
                    } actions: {
                        Button("Add Player") { isAddingPlayer = true }
                            .buttonStyle(.borderedProminent)
                    }
                } else {
                    List {
                        ForEach(players) { player in
                            Button {
                                editingPlayer = player
                            } label: {
                                PlayerRow(player: player)
                            }
                            .buttonStyle(.plain)
                        }
                        .onDelete(perform: deletePlayers)
                    }
                }
            }
            .navigationTitle("Players")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isAddingPlayer = true
                    } label: {
                        Label("Add Player", systemImage: "plus")
                    }
                }
                if !players.isEmpty {
                    ToolbarItem(placement: .topBarLeading) { EditButton() }
                }
            }
            .sheet(isPresented: $isAddingPlayer) {
                PlayerEditView(player: nil)
            }
            .sheet(item: $editingPlayer) { player in
                PlayerEditView(player: player)
            }
        }
    }

    private func deletePlayers(at offsets: IndexSet) {
        for index in offsets {
            modelContext.delete(players[index])
        }
    }
}

private struct PlayerRow: View {
    let player: Player

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "person.circle.fill")
                .font(.title2)
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 3) {
                Text(player.name)
                    .font(.body)
                let teams = player.sortedTeams
                if !teams.isEmpty {
                    Text(teams.map(\.name).joined(separator: ", "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                TallyBadge(tally: player.tally)
            }
            Spacer()
        }
        .contentShape(Rectangle())
    }
}

#Preview {
    PlayersView()
        .modelContainer(SampleData.container)
}
