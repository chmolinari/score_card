//
//  BackupStorage.swift
//  ScoreCard
//
//  File I/O for manual backups. Writes the backup JSON into the app's iCloud
//  Drive container when iCloud is available (so it's backed up and visible in
//  the Files app), otherwise into the app's local Documents. Reads happen via
//  the document picker, which handles iCloud downloads automatically.
//
//  These calls can block (especially the ubiquity-container lookup), so call
//  them off the main thread.
//

import Foundation

struct SavedBackup: Sendable {
    var url: URL
    var isICloud: Bool
}

/// A backup file discovered on disk (iCloud Drive or local), for the in-app list.
struct BackupFile: Identifiable, Sendable {
    var id: URL { url }
    var url: URL
    var name: String
    var date: Date
    var isICloud: Bool
    var sizeBytes: Int

    var formattedSize: String {
        ByteCountFormatter.string(fromByteCount: Int64(sizeBytes), countStyle: .file)
    }
}

enum BackupStorage {
    /// Backups are plain JSON; using the .json extension means the document
    /// picker can show them with no custom UTType registration.
    static let fileExtension = "json"
    static let filePrefix = "ScoreCard-Backup-"

    /// A timestamped backup file name, e.g. "ScoreCard-Backup-2026-06-01-1830.json".
    static func makeFilename(date: Date = .now) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd-HHmmss"
        return "\(filePrefix)\(formatter.string(from: date)).\(fileExtension)"
    }

    /// The iCloud Drive "Documents" folder for this app, or nil if iCloud is
    /// unavailable (e.g. no account signed in, or on a bare simulator).
    static func iCloudDocumentsURL() -> URL? {
        guard let container = FileManager.default.url(forUbiquityContainerIdentifier: nil) else {
            return nil
        }
        let documents = container.appendingPathComponent("Documents", isDirectory: true)
        try? FileManager.default.createDirectory(at: documents, withIntermediateDirectories: true)
        return documents
    }

    /// Local fallback folder, always available.
    static func localBackupsURL() -> URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let folder = documents.appendingPathComponent("Backups", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    /// Write backup data, preferring iCloud Drive, falling back to local storage.
    static func write(_ data: Data, filename: String = makeFilename()) throws -> SavedBackup {
        if let iCloud = iCloudDocumentsURL() {
            let url = iCloud.appendingPathComponent(filename)
            try data.write(to: url, options: .atomic)
            return SavedBackup(url: url, isICloud: true)
        }
        let url = localBackupsURL().appendingPathComponent(filename)
        try data.write(to: url, options: .atomic)
        return SavedBackup(url: url, isICloud: false)
    }

    /// Backups found in a single directory (newest first not guaranteed here).
    static func backups(in directory: URL, isICloud: Bool) -> [BackupFile] {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]) else {
            return []
        }
        return urls.compactMap { url in
            guard url.pathExtension == fileExtension, url.lastPathComponent.hasPrefix(filePrefix) else { return nil }
            let values = try? url.resourceValues(forKeys: Set(keys))
            return BackupFile(url: url,
                              name: url.lastPathComponent,
                              date: values?.contentModificationDate ?? .distantPast,
                              isICloud: isICloud,
                              sizeBytes: values?.fileSize ?? 0)
        }
    }

    /// All backups across iCloud Drive and local storage, newest first.
    static func listBackups() -> [BackupFile] {
        var all: [BackupFile] = []
        if let iCloud = iCloudDocumentsURL() {
            // Best effort: make sure iCloud files are downloaded so they can be read.
            if let urls = try? FileManager.default.contentsOfDirectory(at: iCloud, includingPropertiesForKeys: nil) {
                for url in urls { try? FileManager.default.startDownloadingUbiquitousItem(at: url) }
            }
            all += backups(in: iCloud, isICloud: true)
        }
        all += backups(in: localBackupsURL(), isICloud: false)
        return all.sorted { $0.date > $1.date }
    }

    /// Delete a backup file.
    static func delete(_ url: URL) throws {
        try FileManager.default.removeItem(at: url)
    }

    /// Read a backup file, coordinating with the system so an iCloud file that
    /// isn't downloaded yet is fetched first. `url` may be security-scoped (from
    /// the document picker).
    static func read(url: URL) throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        var coordinationError: NSError?
        var data: Data?
        var readError: Error?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { readURL in
            do { data = try Data(contentsOf: readURL) } catch { readError = error }
        }
        if let coordinationError { throw coordinationError }
        if let readError { throw readError }
        guard let data else { throw CocoaError(.fileReadUnknown) }
        return data
    }
}
