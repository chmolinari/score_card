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

            Section("Final Standings") {
                ForEach(Array(game.rankedParticipants.enumerated()), id: \.element.persistentModelID) { index, participant in
                    HStack(spacing: 12) {
                        Image(systemName: index == 0 ? "trophy.fill" : "\(index + 1).circle")
                            .font(.title3)
                            .foregroundStyle(index == 0 ? .yellow : .secondary)
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
