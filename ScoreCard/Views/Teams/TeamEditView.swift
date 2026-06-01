//
//  TeamEditView.swift
//  ScoreCard
//
//  Sheet for creating or editing a team: set a name and pick member players.
//

import SwiftUI
import SwiftData

struct TeamEditView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    /// nil → creating a new team; non-nil → editing that team.
    let team: Team?

    /// Called with the newly inserted team when creating one (not when editing).
    var onCreate: ((Team) -> Void)? = nil

    @Query(sort: \Player.name) private var allPlayers: [Player]

    @State private var name: String = ""
    @State private var selectedPlayerIDs: Set<PersistentIdentifier> = []
    @State private var isCreatingPlayer = false

    private var isEditing: Bool { team != nil }
    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Team name", text: $name)
                        .textInputAutocapitalization(.words)
                }

                Section {
                    if allPlayers.isEmpty {
                        Text("No players yet. Create one to add it to this team.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(allPlayers) { player in
                            Button {
                                toggle(player)
                            } label: {
                                HStack {
                                    Text(player.name)
                                        .foregroundStyle(.primary)
                                    Spacer()
                                    if selectedPlayerIDs.contains(player.persistentModelID) {
                                        Image(systemName: "checkmark")
                                            .foregroundStyle(.tint)
                                    }
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    Button {
                        isCreatingPlayer = true
                    } label: {
                        Label("New Player", systemImage: "plus")
                    }
                } header: {
                    Text("Members")
                } footer: {
                    Text("A team needs at least one member.")
                }
            }
            .navigationTitle(isEditing ? "Edit Team" : "New Team")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(trimmedName.isEmpty || selectedPlayerIDs.isEmpty)
                }
            }
            .sheet(isPresented: $isCreatingPlayer) {
                PlayerEditView(player: nil)
            }
            .onAppear(perform: loadInitialState)
        }
    }

    private func loadInitialState() {
        guard name.isEmpty, selectedPlayerIDs.isEmpty else { return }
        name = team?.name ?? ""
        selectedPlayerIDs = Set((team?.members ?? []).map(\.persistentModelID))
    }

    private func toggle(_ player: Player) {
        let id = player.persistentModelID
        if selectedPlayerIDs.contains(id) {
            selectedPlayerIDs.remove(id)
        } else {
            selectedPlayerIDs.insert(id)
        }
    }

    private func save() {
        let members = allPlayers.filter { selectedPlayerIDs.contains($0.persistentModelID) }
        if let team {
            team.name = trimmedName
            team.members = members
        } else {
            let newTeam = Team(name: trimmedName, members: members)
            modelContext.insert(newTeam)
            onCreate?(newTeam)
        }
        dismiss()
    }
}

#Preview("New") {
    TeamEditView(team: nil)
        .modelContainer(SampleData.container)
}
