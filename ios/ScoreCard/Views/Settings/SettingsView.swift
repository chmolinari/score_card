//
//  SettingsView.swift
//  ScoreCard
//
//  Surfaces iCloud sync status and basic data stats. Sync itself is automatic
//  (handled by SwiftData + CloudKit); this screen explains what's happening.
//

import SwiftUI
import SwiftData
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(\.modelContext) private var modelContext
    @AppStorage(NegativeScores.storageKey) private var allowNegativeScores = false

    @Query private var players: [Player]
    @Query private var teams: [Team]
    @Query private var games: [Game]

    @State private var accountStatus: CloudAccountStatus = .checking

    @AppStorage(DealingDirection.storageKey) private var dealingDirection: DealingDirection = .counterClockwise
    @AppStorage(DrawDealingRule.storageKey) private var drawDealingRule: DrawDealingRule = .ask

    // Backup / reset state. (Restore lives in BackupListView.)
    @State private var isWorking = false
    @State private var lastBackup: SavedBackup?
    @State private var lastBackupDate: Date?
    @State private var showResetConfirmation = false
    @State private var statusMessage: StatusMessage?

    private var isEmptyStore: Bool { players.isEmpty && teams.isEmpty && games.isEmpty }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 12) {
                        Image(systemName: accountStatus.systemImage)
                            .font(.title2)
                            .foregroundStyle(accountStatus.tint)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("iCloud Sync")
                                .font(.headline)
                            Text(accountStatus.message)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                } footer: {
                    Text("Players, teams, and games are stored on this device and automatically synced and backed up to your private iCloud account. Sign in to iCloud in the Settings app to enable sync across your devices.")
                }

                Section {
                    NavigationLink {
                        HelpView()
                    } label: {
                        Label("How to Use ScoreCard", systemImage: "questionmark.circle")
                    }
                } footer: {
                    Text("A guide to setting up a game, keeping score hand by hand, and correcting a result after the game is over.")
                }

                Section {
                    Picker("Dealing order", selection: $dealingDirection) {
                        ForEach(DealingDirection.allCases) { direction in
                            Label(direction.label, systemImage: direction.systemImage).tag(direction)
                        }
                    }
                    Picker("After a draw", selection: $drawDealingRule) {
                        ForEach(DrawDealingRule.allCases) { rule in
                            Label(rule.label, systemImage: rule.systemImage).tag(rule)
                        }
                    }
                } header: {
                    Text("Gameplay")
                } footer: {
                    Text("The direction the deal passes around the table after each hand, and who deals next when a hand ends in a draw.")
                }

                Section {
                    Toggle("Allow scores below zero", isOn: $allowNegativeScores)
                } header: {
                    Text("Scoring")
                } footer: {
                    Text("When off, a total stops at zero everywhere — subtracting, correcting a finished game, and registering a past one. Turn on for games that go negative, like Spades or Pinochle.")
                }

                Section("Your Data") {
                    LabeledContent("Players", value: "\(players.count)")
                    LabeledContent("Teams", value: "\(teams.count)")
                    LabeledContent("Games", value: "\(games.count)")
                }

                backupSection
                resetSection

                Section("About") {
                    LabeledContent("App", value: "ScoreCard")
                    LabeledContent("Version", value: appVersion)
                }
            }
            .navigationTitle("Settings")
            .disabled(isWorking)
            .overlay { if isWorking { workingOverlay } }
            .task { await refreshAccountStatus() }
            .confirmationDialog("Delete all data?",
                                isPresented: $showResetConfirmation,
                                titleVisibility: .visible) {
                Button("Delete Everything", role: .destructive) {
                    Task { await reset() }
                }
                Button("Cancel", role: .cancel) { }
            } message: {
                Text("This permanently removes every player, team, and game. Because data syncs to iCloud, it is also removed from your other devices. This can't be undone.")
            }
            .alert(item: $statusMessage) { message in
                Alert(title: Text(message.title), message: Text(message.body), dismissButton: .default(Text("OK")))
            }
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private var backupSection: some View {
        Section {
            Button {
                Task { await backUpNow() }
            } label: {
                Label("Back Up Now", systemImage: "icloud.and.arrow.up")
            }
            .disabled(isEmptyStore)

            if let lastBackup {
                ShareLink(item: lastBackup.url) {
                    Label("Share Latest Backup", systemImage: "square.and.arrow.up")
                }
            }

            NavigationLink {
                BackupListView()
            } label: {
                Label("Restore from Backup…", systemImage: "icloud.and.arrow.down")
            }
        } header: {
            Text("Backup & Restore")
        } footer: {
            Text(backupFooter)
        }
    }

    @ViewBuilder
    private var resetSection: some View {
        Section {
            Button(role: .destructive) {
                showResetConfirmation = true
            } label: {
                Label("Delete All Data", systemImage: "trash")
            }
            .disabled(isEmptyStore)
        } footer: {
            Text("Erases all players, teams, and games to start fresh. Back up first if you might want the data later.")
        }
    }

    private var workingOverlay: some View {
        ProgressView()
            .controlSize(.large)
            .padding(24)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private var backupFooter: String {
        guard let lastBackup, let lastBackupDate else {
            return "Save a snapshot of all your data. When iCloud is available it's stored in iCloud Drive (in the ScoreCard folder) and backed up automatically; otherwise it's saved on this device and you can share it to iCloud Drive."
        }
        let where_ = lastBackup.isICloud ? "iCloud Drive" : "this device"
        return "Last backup: \(GameFormatting.dateTime(lastBackupDate)) — saved to \(where_)."
    }

    // MARK: - Actions

    private func backUpNow() async {
        isWorking = true
        defer { isWorking = false }
        do {
            let data = try BackupService.exportData(from: modelContext)
            let saved = try await Task.detached { try BackupStorage.write(data) }.value
            lastBackup = saved
            lastBackupDate = .now
            let location = saved.isICloud ? "iCloud Drive" : "this device"
            statusMessage = StatusMessage(title: "Backup Complete",
                                          body: "Saved \(players.count) players, \(teams.count) teams, and \(games.count) games to \(location).")
        } catch {
            statusMessage = StatusMessage(title: "Backup Failed", body: error.localizedDescription)
        }
    }

    private func reset() async {
        isWorking = true
        defer { isWorking = false }
        do {
            try BackupService.eraseAll(in: modelContext)
            lastBackup = nil
            lastBackupDate = nil
            statusMessage = StatusMessage(title: "Data Deleted", body: "All players, teams, and games have been removed.")
        } catch {
            statusMessage = StatusMessage(title: "Couldn't Delete Data", body: error.localizedDescription)
        }
    }

    private var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }

    private func refreshAccountStatus() async {
        accountStatus = await CloudAccountStatus.current()
    }
}

/// A friendly summary of the user's iCloud account availability for sync.
enum CloudAccountStatus {
    case checking
    case available
    case noAccount
    case restricted
    case unavailable

    var message: String {
        switch self {
        case .checking: return "Checking iCloud status…"
        case .available: return "Active. Your data syncs across your devices."
        case .noAccount: return "Not signed in to iCloud. Sign in to enable sync."
        case .restricted: return "iCloud is restricted on this device."
        case .unavailable: return "iCloud is currently unavailable."
        }
    }

    var systemImage: String {
        switch self {
        case .checking: return "arrow.triangle.2.circlepath.icloud"
        case .available: return "checkmark.icloud.fill"
        case .noAccount, .unavailable: return "xmark.icloud"
        case .restricted: return "exclamationmark.icloud"
        }
    }

    var tint: Color {
        switch self {
        case .available: return .green
        case .checking: return .secondary
        default: return .orange
        }
    }

    /// Queries CloudKit for the current account status. Imported lazily so the
    /// rest of the app doesn't depend on CloudKit directly.
    static func current() async -> CloudAccountStatus {
        await CloudKitStatusProbe.accountStatus()
    }
}
