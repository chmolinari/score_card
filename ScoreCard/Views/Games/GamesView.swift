//
//  GamesView.swift
//  ScoreCard
//
//  The Games tab: shows games in progress and the full history of finished
//  games. New games are created from here; tapping a game opens its scoreboard
//  (if open) or its history detail (if closed).
//

import SwiftUI
import SwiftData

struct GamesView: View {
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \Game.createdAt, order: .reverse) private var games: [Game]

    @State private var isCreatingGame = false

    private var openGames: [Game] { games.filter(\.isOpen) }
    private var closedGames: [Game] { games.filter { !$0.isOpen } }

    var body: some View {
        NavigationStack {
            Group {
                if games.isEmpty {
                    ContentUnavailableView {
                        Label("No Games Yet", systemImage: "suit.club.fill")
                    } description: {
                        Text("Start a game to begin keeping score.")
                    } actions: {
                        Button("New Game") { isCreatingGame = true }
                            .buttonStyle(.borderedProminent)
                    }
                } else {
                    List {
                        if !openGames.isEmpty {
                            Section("In Progress") {
                                ForEach(openGames) { game in
                                    NavigationLink {
                                        GameScoreboardView(game: game)
                                    } label: {
                                        GameRow(game: game)
                                    }
                                }
                                .onDelete { delete(openGames, at: $0) }
                            }
                        }
                        if !closedGames.isEmpty {
                            Section("History") {
                                ForEach(closedGames) { game in
                                    NavigationLink {
                                        GameDetailView(game: game)
                                    } label: {
                                        GameRow(game: game)
                                    }
                                }
                                .onDelete { delete(closedGames, at: $0) }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Games")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isCreatingGame = true
                    } label: {
                        Label("New Game", systemImage: "plus")
                    }
                }
            }
            .sheet(isPresented: $isCreatingGame) {
                NewGameView()
            }
        }
    }

    private func delete(_ source: [Game], at offsets: IndexSet) {
        for index in offsets {
            modelContext.delete(source[index])
        }
    }
}

private struct GameRow: View {
    let game: Game

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(game.title)
                    .font(.headline)
                Spacer()
                if game.isOpen {
                    Label("Live", systemImage: "dot.radiowaves.left.and.right")
                        .labelStyle(.titleAndIcon)
                        .font(.caption.bold())
                        .foregroundStyle(.green)
                } else if let leader = game.leader {
                    Label(leader.displayName, systemImage: "trophy.fill")
                        .labelStyle(.titleAndIcon)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Text(scoreSummary)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            HStack(spacing: 12) {
                Label(GameFormatting.dateTime(game.createdAt), systemImage: "calendar")
                if let place = game.locationName {
                    Label(place, systemImage: "mappin.and.ellipse")
                        .lineLimit(1)
                }
                if game.hasTarget, let target = game.targetPoints {
                    Label("to \(target)", systemImage: "flag.checkered")
                }
            }
            .font(.caption2)
            .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 2)
    }

    private var scoreSummary: String {
        let parts = game.rankedParticipants.map { "\($0.displayName) \($0.totalScore)" }
        return parts.isEmpty ? "No participants" : parts.joined(separator: " · ")
    }
}

#Preview {
    GamesView()
        .modelContainer(SampleData.container)
}
