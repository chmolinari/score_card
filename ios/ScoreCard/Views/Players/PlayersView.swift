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
    /// Players a swipe has proposed deleting, held until the user confirms.
    /// Resolved to objects up front — re-reading `sortedPlayers` by index while
    /// deleting would walk a list that is shrinking underneath it.
    @State private var pendingDeletion: [Player] = []

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
            .confirmationDialog(deletionTitle,
                                isPresented: showingDeleteConfirmation,
                                titleVisibility: .visible) {
                Button("Delete Player", role: .destructive) { commitDeletion() }
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
        guard let player = pendingDeletion.first else { return "" }
        return pendingDeletion.count == 1 ? "Delete \(player.name)?" : "Delete \(pendingDeletion.count) players?"
    }

    /// Spells out what else the delete changes: which teams lose a member, and
    /// which are left too small to play. Because the store syncs, this reaches
    /// the user's other devices too.
    private var deletionMessage: String {
        let bodies = pendingDeletion.map {
            RosterCheck.playerDeletionMessage(playerName: $0.name,
                                              impacts: RosterCheck.impact(ofDeleting: $0))
        }
        return bodies.joined(separator: " ")
            + " Because data syncs to iCloud, this also removes them from your other devices. This can't be undone."
    }

    private func commitDeletion() {
        for player in pendingDeletion { modelContext.delete(player) }
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
    private func deletePlayers(at offsets: IndexSet) {
        let sorted = sortedPlayers
        pendingDeletion = offsets.compactMap { sorted.indices.contains($0) ? sorted[$0] : nil }
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
