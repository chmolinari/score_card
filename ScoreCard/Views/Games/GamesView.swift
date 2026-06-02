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
    /// Set by the New Game flow while its sheet is still up; promoted to
    /// `openedGame` after the sheet dismisses to avoid a present/push race.
    @State private var pendingGame: Game?
    /// Drives navigation straight into a just-created game's scoreboard.
    @State private var openedGame: Game?

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
                            Section {
                                ForEach(openGames) { game in
                                    NavigationLink {
                                        GameScoreboardView(game: game)
                                    } label: {
                                        GameRow(game: game)
                                    }
                                    .cardRow()
                                }
                                .onDelete { delete(openGames, at: $0) }
                            } header: {
                                PlayfulSectionHeader(title: "In Progress",
                                                     systemImage: "dot.radiowaves.left.and.right")
                            }
                        }
                        if !closedGames.isEmpty {
                            Section {
                                ForEach(closedGames) { game in
                                    NavigationLink {
                                        GameDetailView(game: game)
                                    } label: {
                                        GameRow(game: game)
                                    }
                                    .cardRow()
                                }
                                .onDelete { delete(closedGames, at: $0) }
                            } header: {
                                PlayfulSectionHeader(title: "History",
                                                     systemImage: "clock.arrow.circlepath")
                            }
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
            .background(AppBackground())
            .navigationTitle("Games")
            .navigationDestination(item: $openedGame) { game in
                GameScoreboardView(game: game)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isCreatingGame = true
                    } label: {
                        Label("New Game", systemImage: "plus")
                    }
                }
            }
            .sheet(isPresented: $isCreatingGame, onDismiss: {
                // Push the new game's scoreboard only once the sheet is fully
                // gone — setting the destination while the sheet is still up can
                // drop the push.
                if let pendingGame {
                    openedGame = pendingGame
                    self.pendingGame = nil
                }
            }) {
                NewGameView { game in pendingGame = game }
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
        HStack(spacing: 14) {
            Avatar(name: game.title,
                   systemImage: game.isOpen ? "suit.club.fill" : "checkmark.seal.fill",
                   size: 46)

            VStack(alignment: .leading, spacing: 5) {
                HStack(alignment: .firstTextBaseline) {
                    Text(game.title)
                        .font(.headline)
                    Spacer()
                    statusBadge
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
        }
        .cardTile()
    }

    @ViewBuilder
    private var statusBadge: some View {
        if game.isOpen {
            Label("Live", systemImage: "dot.radiowaves.left.and.right")
                .labelStyle(.titleAndIcon)
                .font(.caption2.bold())
                .foregroundStyle(Theme.teal)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Theme.teal.opacity(0.15), in: Capsule())
        } else if game.isDraw {
            Label("Draw", systemImage: "equal.circle.fill")
                .labelStyle(.titleAndIcon)
                .font(.caption2.bold())
                .foregroundStyle(Theme.plum)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Theme.plum.opacity(0.15), in: Capsule())
        } else if let leader = game.leader {
            Label(leader.displayName, systemImage: "trophy.fill")
                .labelStyle(.titleAndIcon)
                .font(.caption2.bold())
                .foregroundStyle(Theme.amber)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Theme.amber.opacity(0.15), in: Capsule())
        }
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
