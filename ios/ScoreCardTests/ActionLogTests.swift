//
//  ActionLogTests.swift
//  ScoreCardTests
//
//  The action log's file behaviour: the format the two platforms share, and the
//  rolling that keeps it inside the size the user chose. Kept in step with the
//  Android ActionLogTest. Specified in `docs/action-log.md`.
//

import Foundation
import Testing
@testable import ScoreCard

struct ActionLogTests {

    /// Each test writes into its own temporary directory, so nothing here can
    /// touch the log belonging to the app on this machine.
    private func makeLog() throws -> (ActionLog, URL) {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("actionlog-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return (ActionLog(directory: directory), directory)
    }

    private func entry(_ action: String = "scoreAdded", gameId: String? = "7") -> ActionLogEntry {
        ActionLogEntry(ts: .now, action: action, entity: "ScoreEntry", entityId: "1",
                       gameId: gameId, name: "Bassano", detail: ["points": "1"])
    }

    private func lines(_ log: ActionLog) throws -> [String] {
        let text = try String(contentsOf: log.currentURL, encoding: .utf8)
        return text.split(separator: "\n").map(String.init)
    }

    @Test func aLineIsOneJsonObjectPerAction() throws {
        let (log, directory) = try makeLog()
        defer { try? FileManager.default.removeItem(at: directory) }

        log.appendAndWait(entry())
        log.appendAndWait(entry("scoreRemoved"))

        let written = try lines(log)
        #expect(written.count == 2)
        #expect(written.allSatisfy { $0.hasPrefix("{") && $0.hasSuffix("}") })
        #expect(written[0].contains("\"action\":\"scoreAdded\""))
        #expect(written[1].contains("\"action\":\"scoreRemoved\""))
    }

    @Test func timestampsAreSecondPrecisionUtc() throws {
        let (log, directory) = try makeLog()
        defer { try? FileManager.default.removeItem(at: directory) }

        log.appendAndWait(entry())
        let written = try lines(log)[0]
        // Swift's .iso8601 encoder emits seconds precision; the Android port
        // depends on that, so pin it rather than trusting the default.
        #expect(written.range(of: #""ts":"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""#,
                              options: .regularExpression) != nil,
                "unexpected timestamp format in \(written)")
    }

    @Test func absentOptionalsAreOmittedRatherThanWrittenAsNull() throws {
        let (log, directory) = try makeLog()
        defer { try? FileManager.default.removeItem(at: directory) }

        log.appendAndWait(ActionLogEntry(ts: .now, action: "playerDeleted", entity: "Player",
                                         entityId: "1", gameId: nil, name: nil, detail: nil))
        let written = try lines(log)[0]
        #expect(!written.contains("null"))
        #expect(!written.contains("gameId"))
    }

    @Test func theLogRollsAtHalfTheMaximumAndNeverExceedsIt() throws {
        let (log, directory) = try makeLog()
        defer { try? FileManager.default.removeItem(at: directory) }

        // 1 MiB cap → 512 KiB per segment. Write past two segments' worth and
        // check the pair still fits inside the cap.
        for _ in 0..<6000 { log.appendAndWait(entry(), maxMiB: 1) }

        #expect(FileManager.default.fileExists(atPath: log.previousURL.path),
                "expected a rolled segment")
        #expect(log.totalBytes <= 1 * 1024 * 1024)
    }

    @Test func loweringTheMaximumTakesEffectImmediately() throws {
        let (log, directory) = try makeLog()
        defer { try? FileManager.default.removeItem(at: directory) }

        for _ in 0..<4000 { log.appendAndWait(entry(), maxMiB: 100) }
        #expect(log.totalBytes > 0)

        log.enforceLimit(maxMiB: 1)
        #expect(log.totalBytes <= 1 * 1024 * 1024)
    }

    @Test func recentEntriesComeBackNewestFirst() throws {
        let (log, directory) = try makeLog()
        defer { try? FileManager.default.removeItem(at: directory) }

        log.appendAndWait(entry("first"))
        log.appendAndWait(entry("second"))
        log.appendAndWait(entry("third"))

        #expect(log.recentEntries().map(\.action) == ["third", "second", "first"])
    }

    @Test func aCorruptLineIsSkippedRatherThanLosingTheRest() throws {
        let (log, directory) = try makeLog()
        defer { try? FileManager.default.removeItem(at: directory) }

        log.appendAndWait(entry("good"))
        let handle = try FileHandle(forWritingTo: log.currentURL)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data("{ this is not json\n".utf8))
        try handle.close()
        log.appendAndWait(entry("alsoGood"))

        #expect(log.recentEntries().map(\.action) == ["alsoGood", "good"])
    }

    @Test func deletingRemovesBothSegments() throws {
        let (log, directory) = try makeLog()
        defer { try? FileManager.default.removeItem(at: directory) }

        for _ in 0..<6000 { log.appendAndWait(entry(), maxMiB: 1) }
        #expect(FileManager.default.fileExists(atPath: log.previousURL.path))

        log.deleteAll()
        #expect(!FileManager.default.fileExists(atPath: log.currentURL.path))
        #expect(!FileManager.default.fileExists(atPath: log.previousURL.path))
        #expect(log.totalBytes == 0)
        #expect(log.shareableURLs.isEmpty)
    }

    @Test func namesWithQuotesAndNewlinesStayOnOneLine() throws {
        let (log, directory) = try makeLog()
        defer { try? FileManager.default.removeItem(at: directory) }

        // A player could be named anything; one entry must never become two.
        let odd = "Odd \"Name\"\nwith a newline"
        log.appendAndWait(ActionLogEntry(ts: .now, action: "playerCreated", entity: "Player",
                                         entityId: "1", gameId: nil, name: odd, detail: nil))
        #expect(try lines(log).count == 1)
        #expect(log.recentEntries().first?.name == odd)
    }

    @Test func defaultsAreRecordingOnAtOneHundredMiB() {
        #expect(ActionLogSize.defaultMiB == 100)
        #expect(ActionLogSize.choices.contains(ActionLogSize.defaultMiB))
    }
}
