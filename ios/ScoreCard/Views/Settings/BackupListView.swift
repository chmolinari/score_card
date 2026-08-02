//
//  BackupListView.swift
//  ScoreCard
//
//  Lists previous backups (iCloud Drive + on-device) for one-tap restore, and
//  allows importing a backup file from elsewhere via the document picker.
//

import SwiftUI
import SwiftData
import UniformTypeIdentifiers

struct BackupListView: View {
    @Environment(\.modelContext) private var modelContext

    @State private var backups: [BackupFile] = []
    @State private var isLoading = false
    @State private var isWorking = false

    /// The file awaiting restore confirmation (from the list or the importer).
    @State private var pendingRestoreURL: URL?
    @State private var showRestoreConfirmation = false
    @State private var showImporter = false
    @State private var statusMessage: StatusMessage?

    var body: some View {
        List {
            backupsSection
            Section {
                Button {
                    showImporter = true
                } label: {
                    Label("Import from Files…", systemImage: "folder")
                }
            }
        }
        .navigationTitle("Restore")
        .navigationBarTitleDisplayMode(.inline)
        .disabled(isWorking)
        .overlay { if isWorking { workingOverlay } }
        .task { await load() }
        .refreshable { await load() }
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: [.json],
                      allowsMultipleSelection: false,
                      onCompletion: handleImport)
        .confirmationDialog("Restore this backup?",
                            isPresented: $showRestoreConfirmation,
                            titleVisibility: .visible,
                            actions: restoreDialogActions,
                            message: restoreDialogMessage)
        .alert(item: $statusMessage, content: alert)
    }

    // MARK: - Pieces

    @ViewBuilder
    private var backupsSection: some View {
        if backups.isEmpty && !isLoading {
            Section {
                ContentUnavailableView {
                    Label("No Backups Yet", systemImage: "externaldrive.badge.icloud")
                } description: {
                    Text("Use “Back Up Now” in Settings to create one, or import a backup file you saved elsewhere.")
                }
            }
        } else {
            Section {
                ForEach(backups) { file in
                    Button {
                        pendingRestoreURL = file.url
                        showRestoreConfirmation = true
                    } label: {
                        BackupRow(file: file)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("backupRow")
                }
                .onDelete(perform: deleteBackups)
            } header: {
                Text("Available Backups")
            } footer: {
                Text("Choose a backup to replace all current data with its contents. Swipe to delete a backup file.")
            }
        }
    }

    @ViewBuilder
    private func restoreDialogActions() -> some View {
        Button("Replace All Data", role: .destructive) {
            if let url = pendingRestoreURL { Task { await restore(from: url) } }
        }
        Button("Cancel", role: .cancel) { pendingRestoreURL = nil }
    }

    private func restoreDialogMessage() -> some View {
        Text("Restoring replaces everything currently in ScoreCard with the contents of this backup. This can't be undone.")
    }

    private func alert(_ message: StatusMessage) -> Alert {
        Alert(title: Text(message.title), message: Text(message.body), dismissButton: .default(Text("OK")))
    }

    private var workingOverlay: some View {
        ProgressView()
            .controlSize(.large)
            .padding(24)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Actions

    private func load() async {
        isLoading = true
        backups = await Task.detached { BackupStorage.listBackups() }.value
        isLoading = false
    }

    private func deleteBackups(at offsets: IndexSet) {
        for index in offsets { try? BackupStorage.delete(backups[index].url) }
        backups.remove(atOffsets: offsets)
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            pendingRestoreURL = url
            showRestoreConfirmation = true
        case .failure(let error):
            statusMessage = StatusMessage(title: "Couldn't Open File", body: error.localizedDescription)
        }
    }

    private func restore(from url: URL) async {
        pendingRestoreURL = nil
        isWorking = true
        defer { isWorking = false }
        do {
            let data = try await Task.detached { try BackupStorage.read(url: url) }.value
            // A restore erases then rebuilds the whole store, so the log needs
            // one line saying which file did it — otherwise it reads as a mass
            // deletion followed by a mass creation with no cause.
            ActionLogRecorder.note("userConfirmedRestore",
                                   name: url.lastPathComponent,
                                   detail: ["bytes": "\(data.count)"])
            let snapshot = try BackupService.restore(from: data, into: modelContext)
            statusMessage = StatusMessage(title: "Restore Complete",
                                          body: "Restored \(snapshot.players.count) players, \(snapshot.teams.count) teams, and \(snapshot.games.count) games.")
        } catch {
            statusMessage = StatusMessage(title: "Restore Failed", body: error.localizedDescription)
        }
    }
}

private struct BackupRow: View {
    let file: BackupFile

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: file.isICloud ? "icloud.fill" : "iphone")
                .font(.title3)
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(GameFormatting.dateTime(file.date))
                    .font(.headline)
                Text("\(file.isICloud ? "iCloud Drive" : "On this device") · \(file.formattedSize)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "arrow.down.circle")
                .foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
        .padding(.vertical, 2)
    }
}
