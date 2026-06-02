//
//  GameDetailView.swift
//  ScoreCard
//
//  Read-only history detail for a finished game: final standings, metadata, and
//  an optional map of where it was played.
//

import SwiftUI
import SwiftData
import MapKit

struct GameDetailView: View {
    let game: Game

    var body: some View {
        List {
            Section {
                GameInfoHeader(game: game)
            }

            Section(game.isDraw ? "Final Standings · Draw" : "Final Standings") {
                ForEach(Array(game.rankedParticipants.enumerated()), id: \.element.persistentModelID) { index, participant in
                    HStack(spacing: 12) {
                        standingIcon(rank: index, isTopScorer: topScorerIDs.contains(participant.persistentModelID))
                            .font(.title3)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(participant.displayName).font(.headline)
                            Text(participant.subtitle).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text("\(participant.totalScore)")
                            .font(.system(.title3, design: .rounded).weight(.bold))
                            .monospacedDigit()
                    }
                    .padding(.vertical, 2)
                }
            }

            if let coordinate = game.coordinate {
                Section("Location") {
                    Map(initialPosition: .region(region(for: coordinate))) {
                        Marker(game.locationName ?? game.title, coordinate: coordinate)
                    }
                    .frame(height: 200)
                    .listRowInsets(EdgeInsets())
                }
            }
        }
        .navigationTitle(game.title)
        .navigationBarTitleDisplayMode(.inline)
    }

    /// IDs of the competitor(s) sharing the top score, so a tie can mark every
    /// leader rather than only the first row.
    private var topScorerIDs: Set<PersistentIdentifier> {
        Set(game.topScorers.map(\.persistentModelID))
    }

    /// Standings icon: a shared "equal" mark for every leader in a draw, a trophy
    /// for a sole winner, and a plain rank number for everyone else.
    @ViewBuilder
    private func standingIcon(rank: Int, isTopScorer: Bool) -> some View {
        if game.isDraw && isTopScorer {
            Image(systemName: "equal.circle.fill").foregroundStyle(Theme.plum)
        } else if rank == 0 {
            Image(systemName: "trophy.fill").foregroundStyle(.yellow)
        } else {
            Image(systemName: "\(rank + 1).circle").foregroundStyle(.secondary)
        }
    }

    private func region(for coordinate: CLLocationCoordinate2D) -> MKCoordinateRegion {
        MKCoordinateRegion(center: coordinate,
                           span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02))
    }
}

#Preview {
    NavigationStack {
        if let game = try? SampleData.container.mainContext.fetch(FetchDescriptor<Game>()).first(where: { !$0.isOpen }) {
            GameDetailView(game: game)
        } else {
            Text("No finished game in sample data")
        }
    }
    .modelContainer(SampleData.container)
}
