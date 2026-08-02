//
//  ActionLog.swift
//  ScoreCard
//
//  An on-device audit trail: one line per action that changes stored state, so
//  an incident can be reconstructed afterwards instead of inferred from backup
//  diffs. Format and rolling behaviour are specified in `docs/action-log.md`
//  and mirrored by the Android port (data/log/ActionLog.kt).
//
//  Three things about where this lives are deliberate:
//
//  * The log is a file, not a SwiftData model. `BackupService.eraseAll` deletes
//    every model individually, so a logged-as-a-model trail would be destroyed
//    by the very reset or restore it most needs to record.
//  * It sits in Application Support, not the iCloud Documents folder
//    `BackupStorage` uses, so it neither syncs nor shows up in Files.
//  * It is not part of `BackupSnapshot`; the backup format version is unchanged.
//

import Foundation

/// One recorded action.
struct ActionLogEntry: Codable, Equatable {
    var ts: Date
    /// Verb naming what happened, e.g. "playerDeleted", "scoreAdded".
    var action: String
    /// Model type the action concerns, e.g. "Player", "ScoreEntry".
    var entity: String
    /// Stable-enough identifier for correlating lines about the same object.
    var entityId: String
    /// The game this action belongs to, when it belongs to one at all. Player
    /// and team actions have none — correlate those by `entityId`/`name`.
    var gameId: String?
    /// Display name at the time of the action, so a line still reads properly
    /// after the object it describes is gone.
    var name: String?
    /// Small free-form extras: points scored, teams affected, a clamped total.
    var detail: [String: String]?
}

/// Sizes offered in Settings. Stored as a plain Int of MiB so the preference
/// key means the same thing on both platforms.
enum ActionLogSize {
    static let choices = [10, 50, 100, 250, 500]
    static let defaultMiB = 100
    static let enabledKey = "actionLogEnabled"
    static let maxMiBKey = "actionLogMaxMiB"
}

/// Appends entries to a rolling pair of files and keeps the total under the
/// configured cap.
///
/// Rolling uses **two segments** rather than trimming one file: dropping the
/// head of a 100 MiB file means rewriting it, which is far too expensive to do
/// on a write path. `actions.jsonl` is the live segment and `actions.1.jsonl`
/// the previous one; each is capped at half the maximum, so the pair never
/// exceeds it.
final class ActionLog: @unchecked Sendable {

    /// The app-wide log. Settable only through `useThrowawayDirectory()`, so a
    /// UI test can exercise the real recorder and the real Settings screens
    /// without writing into the log belonging to whoever owns the device.
    static private(set) var shared = ActionLog()

    /// Point the shared log at a fresh temporary directory. Called once at
    /// launch when the UI-test flag asks for it, and never in production.
    static func useThrowawayDirectory() {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("uitest-actionlog-\(UUID().uuidString)", isDirectory: true)
        shared = ActionLog(directory: directory)
    }

    /// Serialises writes and keeps them off the caller's thread — a save must
    /// never wait on logging.
    private let queue = DispatchQueue(label: "com.christianmolinari.ScoreCard.actionlog")
    private let fileManager = FileManager.default
    private let directory: URL
    private let encoder: JSONEncoder

    /// Injected so tests can write to a temporary directory.
    init(directory: URL? = nil) {
        if let directory {
            self.directory = directory
        } else {
            let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            self.directory = base.appendingPathComponent("Logs", isDirectory: true)
        }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601   // seconds precision, as the backup format requires
        encoder.outputFormatting = [.sortedKeys]
        self.encoder = encoder
    }

    var currentURL: URL { directory.appendingPathComponent("actions.jsonl") }
    var previousURL: URL { directory.appendingPathComponent("actions.1.jsonl") }

    // MARK: - Writing

    /// Append an entry. Never throws and never blocks the caller: a failure to
    /// log must not take the app down or stall a save.
    func append(_ entry: ActionLogEntry, maxMiB: Int = ActionLogSize.defaultMiB) {
        queue.async { [weak self] in
            self?.write(entry, maxMiB: maxMiB)
        }
    }

    /// Synchronous form, for tests that need to observe the file afterwards.
    func appendAndWait(_ entry: ActionLogEntry, maxMiB: Int = ActionLogSize.defaultMiB) {
        queue.sync { write(entry, maxMiB: maxMiB) }
    }

    private func write(_ entry: ActionLogEntry, maxMiB: Int) {
        guard let data = try? encoder.encode(entry) else { return }
        var line = data
        line.append(0x0A)   // newline; the encoder never emits one

        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            if !fileManager.fileExists(atPath: currentURL.path) {
                fileManager.createFile(atPath: currentURL.path, contents: nil)
            }
            let handle = try FileHandle(forWritingTo: currentURL)
            defer { try? handle.close() }
            try handle.seekToEnd()
            try handle.write(contentsOf: line)
        } catch {
            return   // logging is best effort, by design
        }

        rollIfNeeded(maxMiB: maxMiB)
    }

    // MARK: - Rolling

    private func segmentLimit(maxMiB: Int) -> Int {
        max(1, maxMiB) * 1024 * 1024 / 2
    }

    /// Rotate once the live segment fills half the budget, so the pair stays
    /// within the maximum the user chose.
    private func rollIfNeeded(maxMiB: Int) {
        let limit = segmentLimit(maxMiB: maxMiB)
        guard size(of: currentURL) >= limit else { return }
        try? fileManager.removeItem(at: previousURL)
        try? fileManager.moveItem(at: currentURL, to: previousURL)
    }

    /// Applies a newly lowered maximum straight away rather than waiting for the
    /// next write, which might be days later.
    func enforceLimit(maxMiB: Int) {
        queue.sync {
            let limit = segmentLimit(maxMiB: maxMiB)
            if size(of: previousURL) > limit { try? fileManager.removeItem(at: previousURL) }
            if size(of: currentURL) >= limit {
                try? fileManager.removeItem(at: previousURL)
                try? fileManager.moveItem(at: currentURL, to: previousURL)
            }
        }
    }

    // MARK: - Reading and clearing

    private func size(of url: URL) -> Int {
        (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
    }

    /// Bytes currently on disk across both segments.
    var totalBytes: Int {
        queue.sync { size(of: currentURL) + size(of: previousURL) }
    }

    var formattedSize: String {
        ByteCountFormatter.string(fromByteCount: Int64(totalBytes), countStyle: .file)
    }

    /// The most recent `limit` entries, newest first. Only the live segment is
    /// read and only its tail is decoded, so opening the viewer stays instant
    /// even against a full log.
    func recentEntries(limit: Int = 500) -> [ActionLogEntry] {
        queue.sync {
            guard let text = try? String(contentsOf: currentURL, encoding: .utf8) else { return [] }
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            return text
                .split(separator: "\n")
                .suffix(limit)
                .reversed()
                // A truncated or corrupt line is skipped rather than stopping
                // the rest of the log from being read.
                .compactMap { try? decoder.decode(ActionLogEntry.self, from: Data($0.utf8)) }
        }
    }

    /// Remove both segments. Settings only offers this while logging is off, so
    /// a delete can't race a live write.
    func deleteAll() {
        queue.sync {
            try? fileManager.removeItem(at: currentURL)
            try? fileManager.removeItem(at: previousURL)
        }
    }

    /// Both segments oldest-first, for sharing off the device.
    var shareableURLs: [URL] {
        queue.sync {
            [previousURL, currentURL].filter { fileManager.fileExists(atPath: $0.path) }
        }
    }
}
