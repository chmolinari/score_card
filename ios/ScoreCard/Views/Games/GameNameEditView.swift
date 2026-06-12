//
//  GameNameEditView.swift
//  ScoreCard
//
//  Sheet for adding a new game name or renaming an existing one — the editable
//  list behind the New Game name picker. Mirrors PlayerEditView.
//

import SwiftUI
import SwiftData

struct GameNameEditView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    /// nil → adding a new game name; non-nil → renaming that one.
    let gameName: GameName?

    /// Called with the newly inserted game name when adding one (not when
    /// renaming), so New Game can auto-select what was just created.
    var onCreate: ((GameName) -> Void)? = nil

    @Query private var allGameNames: [GameName]

    @State private var name: String = ""

    private var isEditing: Bool { gameName != nil }
    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// A non-nil message when the typed name collides with another game name
    /// (case-insensitive). Game names must be unique so the list has no dupes.
    private var nameError: String? {
        guard !trimmedName.isEmpty else { return nil }
        let clashes = allGameNames.contains { other in
            other.persistentModelID != gameName?.persistentModelID
                && other.name.localizedCaseInsensitiveCompare(trimmedName) == .orderedSame
        }
        return clashes ? "A game named “\(trimmedName)” already exists." : nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Game name (e.g. Scopa, Briscola)", text: $name)
                        .textInputAutocapitalization(.words)
                } header: {
                    Text("Name")
                } footer: {
                    if let nameError {
                        Text(nameError).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(isEditing ? "Edit Game Name" : "New Game Name")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(trimmedName.isEmpty || nameError != nil)
                }
            }
            .onAppear { name = gameName?.name ?? "" }
        }
    }

    private func save() {
        guard !trimmedName.isEmpty, nameError == nil else { return }
        if let gameName {
            gameName.name = trimmedName
        } else {
            let newGameName = GameName(name: trimmedName)
            modelContext.insert(newGameName)
            onCreate?(newGameName)
        }
        dismiss()
    }
}

#Preview("New") {
    GameNameEditView(gameName: nil)
        .modelContainer(SampleData.container)
}
