//
//  SeatingArrangementView.swift
//  ScoreCard
//
//  Asks the user to arrange the table's seating order before a game starts. The
//  first dealer is picked at random; the user drags the remaining people into
//  the order they sit, going counter-clockwise from the dealer. The deal then
//  passes counter-clockwise each hand.
//
//  Reusable: the caller supplies the people and handles persistence via
//  onConfirm, which receives the ordered seating with index 0 = first dealer.
//

import SwiftUI
import SwiftData

struct SeatingArrangementView: View {
    let people: [Player]
    var confirmTitle: String = "Start Game"
    var direction: DealingDirection = .counterClockwise
    let onConfirm: ([Player]) async -> Void

    @State private var dealer: Player?
    @State private var others: [Player] = []
    @State private var isSaving = false
    @State private var didInitialize = false
    /// Drives the manual dealer-picker sheet (tap the dealer card to open it).
    @State private var isPickingDealer = false

    var body: some View {
        List {
            Section {
                if let dealer {
                    Button {
                        isPickingDealer = true
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "hand.draw.fill")
                                .font(.title3)
                                .foregroundStyle(.tint)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(dealer.name).font(.headline).foregroundStyle(.primary)
                                Text("First dealer · tap to choose").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button {
                                reshuffleDealer()
                            } label: {
                                Label("Shuffle", systemImage: "shuffle")
                                    .labelStyle(.iconOnly)
                            }
                            .buttonStyle(.borderless)
                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                        .padding(.vertical, 2)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                Text("Dealer")
            } footer: {
                Text("The first dealer is random — tap the dealer to pick someone else, or shuffle for another random pick. Drag the others into the order they sit around the table — the deal passes \(direction.adverb) each hand. In team games, the dealer is still an individual player.")
            }

            Section("Then, counter-clockwise") {
                if others.isEmpty {
                    Text("No other players.").foregroundStyle(.secondary)
                } else {
                    ForEach(Array(others.enumerated()), id: \.element.persistentModelID) { index, player in
                        HStack(spacing: 12) {
                            Text("\(index + 2)")
                                .font(.subheadline.monospacedDigit())
                                .foregroundStyle(.secondary)
                                .frame(width: 24, alignment: .trailing)
                            Text(player.name)
                            Spacer()
                        }
                    }
                    .onMove { others.move(fromOffsets: $0, toOffset: $1) }
                }
            }
        }
        .environment(\.editMode, .constant(.active))
        .navigationTitle("Seating & Dealer")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(confirmTitle) { Task { await confirm() } }
                    .disabled(dealer == nil || isSaving)
            }
        }
        .overlay {
            if isSaving {
                ProgressView()
                    .controlSize(.large)
                    .padding(24)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
            }
        }
        .sheet(isPresented: $isPickingDealer) { dealerPicker }
        .onAppear(perform: initializeIfNeeded)
    }

    /// List of everyone at the table for manually choosing the first dealer.
    private var dealerPicker: some View {
        NavigationStack {
            List {
                ForEach(people, id: \.persistentModelID) { person in
                    Button {
                        selectDealer(person)
                        isPickingDealer = false
                    } label: {
                        HStack {
                            Text(person.name).foregroundStyle(.primary)
                            Spacer()
                            if person.persistentModelID == dealer?.persistentModelID {
                                Image(systemName: "checkmark").foregroundStyle(.tint)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .navigationTitle("Choose First Dealer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { isPickingDealer = false }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func initializeIfNeeded() {
        guard !didInitialize else { return }
        didInitialize = true
        reshuffleDealer()
    }

    private func reshuffleDealer() {
        guard !people.isEmpty else { return }
        var pool = people
        let index = Int.random(in: 0..<pool.count)
        dealer = pool.remove(at: index)
        others = pool
    }

    /// Manually set `player` as the first dealer, keeping the rest of the seating
    /// order stable: the previous dealer simply takes the chosen player's old
    /// place in the line-up.
    private func selectDealer(_ player: Player) {
        guard let current = dealer, current.persistentModelID != player.persistentModelID else { return }
        if let index = others.firstIndex(where: { $0.persistentModelID == player.persistentModelID }) {
            others[index] = current
        }
        dealer = player
    }

    private func confirm() async {
        guard let dealer else { return }
        isSaving = true
        defer { isSaving = false }
        await onConfirm([dealer] + others)
    }
}
