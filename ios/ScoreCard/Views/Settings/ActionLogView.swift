//
//  ActionLogView.swift
//  ScoreCard
//
//  Reads back the action log, newest first. Only the tail of the live segment
//  is decoded (see `ActionLog.recentEntries`), so this opens instantly even
//  when the log is at its 100 MiB maximum.
//

import SwiftUI

struct ActionLogView: View {
    @State private var entries: [ActionLogEntry] = []
    @State private var isLoading = true

    var body: some View {
        List {
            if isLoading {
                HStack {
                    ProgressView()
                    Text("Reading log…").foregroundStyle(.secondary)
                }
            } else if entries.isEmpty {
                ContentUnavailableView {
                    Label("No Actions Recorded", systemImage: "doc.text.magnifyingglass")
                } description: {
                    Text("Actions appear here as you use the app. If recording is switched off in Settings, nothing new is added.")
                }
            } else {
                Section {
                    ForEach(Array(entries.enumerated()), id: \.offset) { _, entry in
                        row(for: entry)
                    }
                } footer: {
                    Text("Showing the most recent \(entries.count) actions. Use “Share Log” in Settings for the full file.")
                }
            }
        }
        .navigationTitle("Action Log")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            // Off the main actor: reading and decoding shouldn't stall the push
            // animation on a large log.
            let loaded = await Task.detached { ActionLog.shared.recentEntries() }.value
            entries = loaded
            isLoading = false
        }
    }

    private func row(for entry: ActionLogEntry) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(entry.action)
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text(GameFormatting.dateTime(entry.ts))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            if let name = entry.name, !name.isEmpty {
                Text(name).font(.callout)
            }
            if let subtitle = subtitle(for: entry) {
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    /// The game id plus any extras, on one line — enough to correlate rows
    /// without opening the raw file.
    private func subtitle(for entry: ActionLogEntry) -> String? {
        var parts: [String] = []
        if let gameId = entry.gameId { parts.append("game \(gameId)") }
        if let detail = entry.detail {
            parts += detail.sorted { $0.key < $1.key }.map { "\($0.key): \($0.value)" }
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

#Preview {
    NavigationStack { ActionLogView() }
}
