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

    // Action log. Defaults here are the shipped defaults: recording on, 100 MiB.
    @AppStorage(ActionLogSize.enabledKey) private var actionLogEnabled = true
    @AppStorage(ActionLogSize.maxMiBKey) private var actionLogMaxMiB = ActionLogSize.defaultMiB
    @State private var showLogDeleteConfirmation = false

    // Backup retention. Only this device's own backups are ever pruned — the
    // iCloud folder is shared with the user's other devices.
    @AppStorage(BackupRetention.countKey) private var backupsKept = BackupRetention.defaultKept
    @State private var pendingPrune: [BackupFile] = []
    /// The number the last prompt was measured against, so a reduction is
    /// judged against where the user started rather than the previous detent.
    @State private var appliedBackupsKept = BackupRetention.storedCount()
    /// Debounces the wheel: cancelled and restarted on every value it passes.
    @State private var pruneCheck: Task<Void, Never>?
    /// Re-read after a delete so the row's size stops showing bytes that are gone.
    @State private var logSizeRevision = 0

    private var logSize: String {
        _ = logSizeRevision
        return ActionLog.shared.formattedSize
    }

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
                loggingSection
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
            .confirmationDialog("Delete the action log?",
                                isPresented: $showLogDeleteConfirmation,
                                titleVisibility: .visible) {
                Button("Delete Log", role: .destructive) {
                    ActionLog.shared.deleteAll()
                    logSizeRevision += 1
                }
                Button("Cancel", role: .cancel) { }
            } message: {
                Text("This removes the record of past actions from this device. Your players, teams and games are not affected.")
            }
            .confirmationDialog(pruneTitle,
                                isPresented: showingPruneConfirmation,
                                titleVisibility: .visible) {
                Button("Delete \(pendingPrune.count)", role: .destructive) { commitPrune() }
                Button("Cancel", role: .cancel) { pendingPrune = [] }
            } message: {
                Text(pruneMessage)
            }
            // Lowering the number applies now rather than at the next backup,
            // so the setting always means what it says — but only ever to this
            // device's own backups, and only after the user agrees.
            // Spinning the wheel reports every value it passes through, so the
            // question is asked once the choice settles rather than at each
            // detent — otherwise winding from ten down to two puts eight
            // confirmations in the user's way.
            .onChange(of: backupsKept) { _, newValue in
                pruneCheck?.cancel()
                pruneCheck = Task {
                    try? await Task.sleep(for: .milliseconds(600))
                    guard !Task.isCancelled else { return }
                    await evaluatePrune(for: newValue)
                }
            }
            .onDisappear { pruneCheck?.cancel() }
            // A newly lowered maximum applies now, not at the next write.
            .onChange(of: actionLogMaxMiB) { _, newValue in
                ActionLog.shared.enforceLimit(maxMiB: newValue)
                logSizeRevision += 1
            }
            .onChange(of: actionLogEnabled) { _, newValue in
                // Forced: turning recording off must leave a note saying so,
                // rather than the log just stopping with no explanation.
                ActionLogRecorder.note(newValue ? "loggingEnabled" : "loggingDisabled", force: true)
            }
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private var backupSection: some View {
        Section {
            Picker("Keep backups", selection: $backupsKept) {
                ForEach(BackupRetention.minimumKept...BackupRetention.maximumKept, id: \.self) { count in
                    Text(count == 1 ? "1 (only the latest)" : "\(count)").tag(count)
                }
            }
            // A pushed list rather than an inline wheel: a wheel sitting in a
            // Form swallows vertical drags, so everything below it becomes
            // unreachable by scrolling. This still commits in one move, which
            // is the point — the +/- buttons it replaced asked the question at
            // every number on the way down.
            .pickerStyle(.navigationLink)
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

    /// The action log: an on-device record of everything that changes stored
    /// data, kept so an odd result can be traced afterwards. See
    /// `docs/action-log.md`.
    @ViewBuilder
    private var loggingSection: some View {
        Section {
            Toggle("Record actions", isOn: $actionLogEnabled)

            Picker("Maximum size", selection: $actionLogMaxMiB) {
                ForEach(ActionLogSize.choices, id: \.self) { size in
                    Text("\(size) MiB").tag(size)
                }
            }
            .disabled(!actionLogEnabled)

            NavigationLink {
                ActionLogView()
            } label: {
                LabeledContent("View Log", value: logSize)
            }

            ShareLink(items: ActionLog.shared.shareableURLs) {
                Label("Share Log", systemImage: "square.and.arrow.up")
            }
            .disabled(ActionLog.shared.shareableURLs.isEmpty)

            // Deleting is offered only while recording is off, so a delete can
            // never race a live write.
            Button(role: .destructive) {
                showLogDeleteConfirmation = true
            } label: {
                Label("Delete Log", systemImage: "trash")
            }
            .disabled(actionLogEnabled || ActionLog.shared.shareableURLs.isEmpty)
        } header: {
            Text("Logging")
        } footer: {
            Text(actionLogEnabled
                 ? "Records every change to your players, teams and games — including each score — with the time it happened, so an unexpected result can be traced later. Kept on this device only, never synced, and never included in a backup. The oldest entries are dropped once the log reaches its maximum size. Turn recording off to delete it."
                 : "Recording is off, so nothing new is being written. The existing log is kept until you delete it.")
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

    // MARK: - Backup retention

    private var showingPruneConfirmation: Binding<Bool> {
        Binding(get: { !pendingPrune.isEmpty },
                set: { if !$0 { pendingPrune = [] } })
    }

    private var pruneTitle: String {
        pendingPrune.count == 1 ? "Delete 1 older backup?" : "Delete \(pendingPrune.count) older backups?"
    }

    private var pruneMessage: String {
        let oldest = pendingPrune.last.map { GameFormatting.dateTime($0.date) }
        var message = "Keeping only the \(backupsKept) most recent removes "
            + (pendingPrune.count == 1 ? "1 backup file" : "\(pendingPrune.count) backup files")
        if let oldest { message += ", the oldest from \(oldest)" }
        // Worth saying plainly: the folder is shared, so this is the one thing
        // the user might reasonably fear, and it is exactly what does not happen.
        return message + ". Only backups made on this device are removed — any made on your "
            + "other devices, and any made before this setting existed, are left alone."
    }

    /// Works out whether the settled number leaves anything to remove, and asks
    /// if so. Only a *reduction* can, so raising the number never prompts even
    /// when the folder already holds more files than the new limit.
    @MainActor
    private func evaluatePrune(for settled: Int) async {
        defer { appliedBackupsKept = settled }
        guard settled < appliedBackupsKept else { return }
        // Off the main thread: listing backups walks the iCloud ubiquity
        // container, which BackupStorage warns can block.
        pendingPrune = await Task.detached {
            BackupStorage.prunableBackups(keeping: settled)
        }.value
    }

    private func commitPrune() {
        let doomed = pendingPrune
        pendingPrune = []
        for backup in doomed {
            guard (try? BackupStorage.delete(backup.url)) != nil else { continue }
            ActionLogRecorder.note("backupPruned", name: backup.name)
        }
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
            // Trim this device's older backups now the new one is safely written.
            let kept = backupsKept
            let pruned = await Task.detached { BackupStorage.prune(keeping: kept) }.value
            for name in pruned { ActionLogRecorder.note("backupPruned", name: name) }
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
            // Noted before the wipe: eraseAll deletes every object individually,
            // so without this the log would show a flood of deletions with
            // nothing saying they were one deliberate reset.
            ActionLogRecorder.note("userConfirmedResetAllData",
                                   detail: ["players": "\(players.count)",
                                            "teams": "\(teams.count)",
                                            "games": "\(games.count)"])
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
