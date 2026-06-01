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

    var body: some View {
        List {
            Section {
                if let dealer {
                    HStack(spacing: 12) {
                        Image(systemName: "hand.draw.fill")
                            .font(.title3)
                            .foregroundStyle(.tint)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(dealer.name).font(.headline)
                            Text("First dealer · chosen at random").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button {
                            reshuffleDealer()
                        } label: {
                            Label("Shuffle", systemImage: "shuffle")
                                .labelStyle(.iconOnly)
                        }
                        .buttonStyle(.borderless)
                    }
                    .padding(.vertical, 2)
                }
            } header: {
                Text("Dealer")
            } footer: {
                Text("The first dealer is random. Drag the others into the order they sit around the table — the deal passes \(direction.adverb) each hand. In team games, the dealer is still an individual player.")
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
        .onAppear(perform: initializeIfNeeded)
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

    private func confirm() async {
        guard let dealer else { return }
        isSaving = true
        defer { isSaving = false }
        await onConfirm([dealer] + others)
    }
}
