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

    // Address resolved on the fly for games that only stored raw coordinates
    // (e.g. captured before reverse geocoding succeeded). We never show raw
    // latitude/longitude — only a human-readable address.
    @State private var resolvedAddress: String?

    /// The address to show: the one captured with the game, or one we reverse
    /// geocode lazily from its coordinate.
    private var displayAddress: String? {
        game.locationName ?? resolvedAddress
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                statusChip
                if game.hasTarget, let target = game.targetPoints {
                    chip(text: "First to \(target)", systemImage: "flag.checkered", tint: .blue)
                } else {
                    chip(text: "Open-ended", systemImage: "infinity", tint: .blue)
                }
                // Mirrors the card badge in the games list: a corrected result
                // must be recognizable as one wherever the game is shown.
                if game.isEdited {
                    chip(text: "Edited", systemImage: "pencil", tint: Theme.sky)
                }
            }

            Label(GameFormatting.dateTime(game.createdAt, dateOnly: game.playedDateOnly),
                  systemImage: "calendar")
                .font(.subheadline)

            if let place = displayAddress {
                Label(place, systemImage: "mappin.and.ellipse")
                    .font(.subheadline)
            }

            // For a registered past game closedAt equals createdAt, so the line
            // would just repeat the date above with a meaningless duration.
            if let closedAt = game.closedAt, closedAt > game.createdAt {
                Label("Ended \(GameFormatting.dateTime(closedAt)) · \(GameFormatting.duration(from: game.createdAt, to: closedAt))",
                      systemImage: "checkmark.circle")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .task(id: game.persistentModelID) { await resolveAddressIfNeeded() }
    }

    /// Reverse geocode the stored coordinate into a street address when the game
    /// has a location but no saved place name. Best-effort and silent on failure.
    private func resolveAddressIfNeeded() async {
        resolvedAddress = nil
        guard game.locationName == nil, let coordinate = game.coordinate else { return }
        let location = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        if let placemark = try? await CLGeocoder().reverseGeocodeLocation(location).first {
            resolvedAddress = LocationManager.describe(placemark)
        }
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

    /// Called after any score mutation so the presenting scoreboard can
    /// invalidate its derived totals deterministically (see
    /// `GameScoreboardView.scoreRevision` — SwiftData does not reliably fire
    /// observation for a to-many relationship mutated via its inverse).
    var onMutate: () -> Void = {}

    @AppStorage(NegativeScores.storageKey) private var allowNegativeScores = false

    @State private var customAmount = 1

    // The Entries list renders from this snapshot instead of reading the
    // SwiftData relationship per render. The relationship is not a reliable
    // List data source on the CloudKit-backed store: deletions reach it on
    // their own schedule (and background sync merges can touch it mid-render),
    // so on iOS 18 the collection view's row bookkeeping could disagree with
    // the data and abort with "invalid number of items in section". The
    // snapshot changes only in `add`/`deleteEntries`, in lockstep with what
    // UIKit animates.
    @State private var entries: [ScoreEntry] = []

    private let quickAmounts = [1, 2, 3, 5, 10]

    /// True when scores are clamped at zero and this participant has nothing left
    /// to subtract — used to disable the subtract controls for clear feedback.
    private var subtractionBlocked: Bool {
        !allowNegativeScores && participant.totalScore <= NegativeScores.floor
    }

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
                                .accessibilityIdentifier("sheetTotal")
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
                                .accessibilityIdentifier("sheetAdd\(amount)")
                        }
                    }
                }

                Section("Quick Subtract") {
                    HStack(spacing: 8) {
                        ForEach(quickAmounts, id: \.self) { amount in
                            Button("-\(amount)") { add(-amount) }
                                .buttonStyle(.bordered)
                                .frame(maxWidth: .infinity)
                                .disabled(subtractionBlocked)
                        }
                    }
                    .tint(.red)
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
                    .disabled(customAmount == 0 || (customAmount < 0 && subtractionBlocked))
                }

                if !entries.isEmpty {
                    Section("Entries") {
                        // Row identity must survive a save: `persistentModelID`
                        // changes from temporary to permanent when an unsaved
                        // entry is first persisted, which would re-identify
                        // rows mid-swipe. ObjectIdentifier sticks to the live
                        // instance for the sheet's lifetime.
                        ForEach(entries, id: \.stableID) { entry in
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
            .onAppear { entries = participant.sortedEntries }
        }
    }

    private func add(_ points: Int) {
        let delta = NegativeScores.effectiveDelta(points: points,
                                                  currentTotal: participant.totalScore,
                                                  allowNegative: allowNegativeScores)
        guard delta != 0 else { return }
        let entry = ScoreEntry(points: delta)
        entry.participant = participant
        modelContext.insert(entry)
        entries = participant.sortedEntries
        onMutate()
    }

    private func deleteEntries(at offsets: IndexSet) {
        let doomed = offsets.map { entries[$0] }
        // Snapshot first: the List must lose exactly the swiped rows in the
        // same update UIKit is animating.
        entries.remove(atOffsets: offsets)
        for entry in doomed {
            modelContext.delete(entry)
        }
        // The scoreboard turns autosave off while a hand is being scored, so
        // without an explicit save the deletion would sit pending in the
        // context (and still count toward `participant.totalScore`) until the
        // next hand boundary. A correction is a deliberate, rare act — persist
        // it like `advanceHand` does.
        try? modelContext.save()
        onMutate()
    }
}

private extension ScoreEntry {
    /// Row identity for the sheet's Entries list: unique per live instance and
    /// stable across saves, unlike `persistentModelID` (see the ForEach above).
    var stableID: ObjectIdentifier { ObjectIdentifier(self) }
}
