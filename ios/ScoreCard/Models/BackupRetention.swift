//
//  BackupRetention.swift
//  ScoreCard
//
//  How many backups to keep, and which ones a device is allowed to remove.
//
//  The second half is the important one. On iOS backups live in the app's
//  *shared* iCloud container: two devices on one account write into the same
//  folder and each sees the other's files. The retention number does not sync
//  (it is a plain user default), so a naive "keep the newest N" would let the
//  device with the smallest number quietly delete the other device's backups —
//  worst exactly when sync is broken and that other backup is the one worth
//  having. So each backup carries a tag identifying the device that wrote it,
//  and a device only ever prunes its own.
//
//  Pure and free of file APIs, so the rules are unit-tested directly; the
//  Android port mirrors this in domain/BackupRetention.kt.
//

import Foundation

enum BackupRetention {

    /// Never fewer than one: the app must not be left with no backup at all.
    static let minimumKept = 1
    static let defaultKept = 10
    /// Upper bound of the Settings stepper.
    static let maximumKept = 50

    static let countKey = "backupRetentionCount"
    static let deviceTagKey = "backupDeviceTag"

    /// Width of the `yyyy-MM-dd-HHmmss` stamp `BackupStorage.makeFilename` writes.
    private static let timestampLength = 17

    /// The device tag in a backup filename, or nil if there isn't one.
    ///
    /// Names look like `ScoreCard-Backup-2026-08-02-181500-a3f19c.json`: a fixed
    /// prefix, a fixed-width timestamp, then the tag. Backups written before
    /// tagging existed stop after the timestamp and return nil — which is what
    /// keeps them out of every prune.
    static func deviceTag(inFilename filename: String,
                          prefix: String = "ScoreCard-Backup-",
                          fileExtension: String = "json") -> String? {
        guard filename.hasPrefix(prefix) else { return nil }
        var body = String(filename.dropFirst(prefix.count))

        let suffix = ".\(fileExtension)"
        guard body.hasSuffix(suffix) else { return nil }
        body = String(body.dropLast(suffix.count))

        guard body.count > timestampLength else { return nil }
        let afterStamp = body.index(body.startIndex, offsetBy: timestampLength)
        guard body[afterStamp] == "-" else { return nil }

        let tag = String(body[body.index(after: afterStamp)...])
        return tag.isEmpty ? nil : tag
    }

    /// Whether this device wrote the backup, and may therefore remove it.
    static func isOwned(filename: String, byDeviceTagged tag: String) -> Bool {
        deviceTag(inFilename: filename) == tag
    }

    /// The backups to delete: this device's own, beyond the newest `keeping`.
    ///
    /// `backups` must already be newest-first — both `listBackups()`
    /// implementations sort that way, and the tests pin that this drops by
    /// position rather than re-reading dates.
    ///
    /// `keeping` is clamped to at least one, so a corrupt or zero stored
    /// preference can never sweep the folder.
    static func surplus<T>(in backups: [T],
                           ownedBy tag: String,
                           keeping: Int,
                           filename: (T) -> String) -> [T] {
        let limit = max(minimumKept, keeping)
        let mine = backups.filter { isOwned(filename: filename($0), byDeviceTagged: tag) }
        guard mine.count > limit else { return [] }
        return Array(mine.dropFirst(limit))
    }

    /// A short, opaque, per-device token. Not the device's name: since iOS 16
    /// the user-assigned name needs a restricted entitlement, and every iPhone
    /// would otherwise call itself "iPhone". Stored in user defaults, which
    /// never sync, so each device naturally ends up with its own.
    static func deviceTag(in defaults: UserDefaults = .standard) -> String {
        if let existing = defaults.string(forKey: deviceTagKey), !existing.isEmpty {
            return existing
        }
        let fresh = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(6)).lowercased()
        defaults.set(fresh, forKey: deviceTagKey)
        return fresh
    }

    /// The stored limit, clamped into range.
    static func storedCount(in defaults: UserDefaults = .standard) -> Int {
        let stored = defaults.integer(forKey: countKey)
        guard stored > 0 else { return defaultKept }
        return min(maximumKept, max(minimumKept, stored))
    }
}
