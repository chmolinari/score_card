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
    @AppStorage(CompetitorSortOrder.playersStorageKey) private var sortOrder: CompetitorSortOrder = .nameAscending

    @State private var editingPlayer: Player?
    @State private var isAddingPlayer = false

    /// Players in the user's chosen order. "Score" sorts can't live in the
    /// `@Query` because the tally is computed on the fly, so we re-sort here.
    private var sortedPlayers: [Player] {
        CompetitorSorter.sorted(players, by: sortOrder, name: \.name, tally: \.tally)
    }

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
                        ForEach(sortedPlayers) { player in
                            Button {
                                editingPlayer = player
                            } label: {
                                PlayerRow(player: player)
                            }
                            .buttonStyle(.plain)
                            .cardRow()
                        }
                        .onDelete(perform: deletePlayers)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
            .background(AppBackground())
            .navigationTitle("Players")
            .toolbar {
                if !players.isEmpty {
                    ToolbarItem(placement: .topBarLeading) { EditButton() }
                    ToolbarItem(placement: .topBarTrailing) { sortMenu }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isAddingPlayer = true
                    } label: {
                        Label("Add Player", systemImage: "plus")
                    }
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

    private func deletePlayers(at offsets: IndexSet) {
        // Offsets index into the displayed (sorted) list, not the raw query.
        for index in offsets {
            modelContext.delete(sortedPlayers[index])
        }
    }
}

private struct PlayerRow: View {
    let player: Player

    var body: some View {
        HStack(spacing: 14) {
            Avatar(name: player.name, size: 46)
            VStack(alignment: .leading, spacing: 4) {
                Text(player.name)
                    .font(.headline)
                let teams = player.sortedTeams
                if !teams.isEmpty {
                    Label(teams.map(\.name).joined(separator: ", "), systemImage: "person.2.fill")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                TallyBadge(tally: player.tally)
            }
            Spacer()
        }
        .cardTile()
        .contentShape(Rectangle())
    }
}

#Preview {
    PlayersView()
        .modelContainer(SampleData.container)
}
