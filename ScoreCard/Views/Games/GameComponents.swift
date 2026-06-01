//
//  GameComponents.swift
//  ScoreCard
//
//  Reusable pieces shared by the scoreboard and history screens: the game info
//  header and the per-competitor scoring sheet.
//

import SwiftUI
import SwiftData
import CoreLocation

/// "+3" / "-2" style signed label for a point amount.
private func signedPoints(_ value: Int) -> String {
    value > 0 ? "+\(value)" : "\(value)"
}

/// Compact summary of a game's metadata: date/time, location, target, status.
struct GameInfoHeader: View {
    let game: Game

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                statusChip
                if game.hasTarget, let target = game.targetPoints {
                    chip(text: "First to \(target)", systemImage: "flag.checkered", tint: .blue)
                } else {
                    chip(text: "Open-ended", systemImage: "infinity", tint: .blue)
                }
            }

            Label(GameFormatting.dateTime(game.createdAt), systemImage: "calendar")
                .font(.subheadline)

            if let place = game.locationName {
                Label(place, systemImage: "mappin.and.ellipse")
                    .font(.subheadline)
            } else if game.coordinate != nil {
                Label(coordinateText, systemImage: "mappin.and.ellipse")
                    .font(.subheadline)
            }

            if let closedAt = game.closedAt {
                Label("Ended \(GameFormatting.dateTime(closedAt)) · \(GameFormatting.duration(from: game.createdAt, to: closedAt))",
                      systemImage: "checkmark.circle")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }

    private var coordinateText: String {
        guard let c = game.coordinate else { return "" }
        return String(format: "%.4f, %.4f", c.latitude, c.longitude)
    }

    @ViewBuilder
    private var statusChip: some View {
        if game.isOpen {
            chip(text: "In Progress", systemImage: "dot.radiowaves.left.and.right", tint: .green)
        } else {
            chip(text: "Finished", systemImage: "checkmark.seal.fill", tint: .secondary)
        }
    }

    private func chip(text: String, systemImage: String, tint: Color) -> some View {
        Label(text, systemImage: systemImage)
            .font(.caption.bold())
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(tint.opacity(0.15), in: Capsule())
            .foregroundStyle(tint == .secondary ? Color.secondary : tint)
    }
}

/// Sheet for adding (or correcting) a competitor's score, with quick chips, a
/// custom amount, and a swipe-to-undo history of this competitor's entries.
struct ParticipantScoringSheet: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @Bindable var participant: GameParticipant

    @State private var customAmount = 1

    private let quickAmounts = [1, 2, 3, 5, 10]

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Spacer()
                        VStack(spacing: 2) {
                            Text("\(participant.totalScore)")
                                .font(.system(size: 54, weight: .bold, design: .rounded))
                                .monospacedDigit()
                            Text(participant.displayName)
                                .font(.headline)
                            Text(participant.subtitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                }

                Section("Quick Add") {
                    HStack(spacing: 8) {
                        ForEach(quickAmounts, id: \.self) { amount in
                            Button("+\(amount)") { add(amount) }
                                .buttonStyle(.borderedProminent)
                                .frame(maxWidth: .infinity)
                        }
                    }
                }

                Section("Custom") {
                    Stepper(value: $customAmount, in: -100...100) {
                        HStack {
                            Text("Amount")
                            Spacer()
                            Text(signedPoints(customAmount))
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
                    }
                    Button {
                        add(customAmount)
                    } label: {
                        Text("Add \(signedPoints(customAmount)) points")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(customAmount == 0)
                }

                if !participant.sortedEntries.isEmpty {
                    Section("Entries") {
                        ForEach(participant.sortedEntries) { entry in
                            HStack {
                                Text(signedPoints(entry.points))
                                    .font(.body.weight(.semibold))
                                    .monospacedDigit()
                                    .foregroundStyle(entry.points >= 0 ? Color.primary : Color.red)
                                Spacer()
                                Text(entry.timestamp, format: .dateTime.hour().minute())
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .onDelete(perform: deleteEntries)
                    }
                }
            }
            .navigationTitle("Add Points")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func add(_ points: Int) {
        guard points != 0 else { return }
        let entry = ScoreEntry(points: points)
        entry.participant = participant
        modelContext.insert(entry)
    }

    private func deleteEntries(at offsets: IndexSet) {
        let entries = participant.sortedEntries
        for index in offsets {
            modelContext.delete(entries[index])
        }
    }
}
