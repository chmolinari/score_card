//
//  GameNameSection.swift
//  ScoreCard
//
//  The game-name picker section shared by the New Game and Register Past Game
//  forms: choose an existing name, swipe-to-delete, or add a new one inline.
//  The parent owns the selection and the add sheet.
//

import SwiftUI
import SwiftData

struct GameNameSection: View {
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \GameName.name) private var gameNames: [GameName]

    @Binding var selectedGameName: GameName?
    var onAddGameName: () -> Void

    var body: some View {
        Section {
            if gameNames.isEmpty {
                Text("No game names yet. Add one to get started.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(gameNames) { gameName in
                    gameNameRow(gameName)
                }
                .onDelete(perform: deleteGameNames)
            }
            Button { onAddGameName() } label: {
                Label("New Game Name", systemImage: "plus")
            }
        } header: {
            Text("Game")
        } footer: {
            Text("Pick the game you're playing, or add a new one. The last name you used is selected by default.")
        }
    }

    private func gameNameRow(_ gameName: GameName) -> some View {
        Button {
            selectedGameName = gameName
        } label: {
            HStack {
                Image(systemName: "suit.club.fill").foregroundStyle(.tint)
                Text(gameName.name).foregroundStyle(.primary)
                Spacer()
                if selectedGameName?.persistentModelID == gameName.persistentModelID {
                    Image(systemName: "checkmark").foregroundStyle(.tint)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func deleteGameNames(at offsets: IndexSet) {
        for index in offsets {
            let gameName = gameNames[index]
            if selectedGameName?.persistentModelID == gameName.persistentModelID {
                selectedGameName = nil
            }
            modelContext.delete(gameName)
        }
    }

    /// Seed the name list from existing games the first time ever, then return
    /// the name to pre-select (the most recently used one). Parents call this
    /// from `.task` each time their sheet opens, passing their own
    /// "has seeded" flag through a local copy (property wrappers can't be
    /// passed inout).
    @MainActor
    static func prepareSelection(in context: ModelContext, hasSeededGameNames: inout Bool) -> GameName? {
        if !hasSeededGameNames {
            GameName.seedFromExistingGames(context: context)
            hasSeededGameNames = true
        }
        let all = (try? context.fetch(FetchDescriptor<GameName>())) ?? []
        return GameNamePicker.defaultSelection(all, lastUsed: { $0.lastUsedAt }, name: { $0.name })
    }
}
