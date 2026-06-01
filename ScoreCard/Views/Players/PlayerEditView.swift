//
//  PlayerEditView.swift
//  ScoreCard
//
//  Sheet for creating a new player or renaming an existing one.
//

import SwiftUI
import SwiftData

struct PlayerEditView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    /// nil → creating a new player; non-nil → editing that player.
    let player: Player?

    /// Called with the newly inserted player when creating one (not when editing).
    /// Lets callers (e.g. New Game) react to a player created on the fly.
    var onCreate: ((Player) -> Void)? = nil

    @State private var name: String = ""

    private var isEditing: Bool { player != nil }
    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Player name", text: $name)
                        .textInputAutocapitalization(.words)
                }
            }
            .navigationTitle(isEditing ? "Edit Player" : "New Player")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(trimmedName.isEmpty)
                }
            }
            .onAppear { name = player?.name ?? "" }
        }
    }

    private func save() {
        if let player {
            player.name = trimmedName
        } else {
            let newPlayer = Player(name: trimmedName)
            modelContext.insert(newPlayer)
            onCreate?(newPlayer)
        }
        dismiss()
    }
}

#Preview("New") {
    PlayerEditView(player: nil)
        .modelContainer(SampleData.container)
}
