//
//  BackupRetentionTests.swift
//  ScoreCardTests
//
//  Backup retention, and above all *whose* backups a device may delete. The
//  iCloud folder is shared between the user's devices, so the ownership rule is
//  the one that stops one phone quietly removing another's history. Kept in
//  step with the Android BackupRetentionTest.
//

import Foundation
import Testing
@testable import ScoreCard

struct BackupRetentionTests {

    private let mine = "a3f19c"
    private let theirs = "b7e402"

    /// Names as `BackupStorage.makeFilename` writes them, newest first.
    private func names(_ tag: String?, count: Int, startHour: Int = 12) -> [String] {
        (0..<count).map { index in
            let stamp = String(format: "2026-08-02-%02d0000", startHour - index)
            if let tag {
                return "ScoreCard-Backup-\(stamp)-\(tag).json"
            }
            return "ScoreCard-Backup-\(stamp).json"   // the pre-retention shape
        }
    }

    // MARK: - Tag parsing

    @Test func aTagRoundTripsThroughTheFilename() {
        let filename = BackupStorage.makeFilename(date: .now, deviceTag: mine)
        #expect(filename.hasPrefix("ScoreCard-Backup-"))
        #expect(filename.hasSuffix(".json"))
        #expect(BackupRetention.deviceTag(inFilename: filename) == mine)
    }

    @Test func aBackupWrittenBeforeTaggingHasNoTag() {
        #expect(BackupRetention.deviceTag(inFilename: "ScoreCard-Backup-2026-07-25-191352.json") == nil)
    }

    @Test func tagParsingIsNotFooledByOtherNames() {
        // Wrong prefix, wrong extension, and a name that stops mid-stamp.
        #expect(BackupRetention.deviceTag(inFilename: "Something-Else-2026-08-02-120000-a3f19c.json") == nil)
        #expect(BackupRetention.deviceTag(inFilename: "ScoreCard-Backup-2026-08-02-120000-a3f19c.txt") == nil)
        #expect(BackupRetention.deviceTag(inFilename: "ScoreCard-Backup-2026-08.json") == nil)
        // A trailing hyphen leaves an empty tag, which is no tag at all.
        #expect(BackupRetention.deviceTag(inFilename: "ScoreCard-Backup-2026-08-02-120000-.json") == nil)
        // A tag may itself contain hyphens; everything after the stamp is the tag.
        #expect(BackupRetention.deviceTag(inFilename: "ScoreCard-Backup-2026-08-02-120000-a3-f1.json") == "a3-f1")
    }

    // MARK: - The retention rule

    @Test func onlyTheOldestBeyondTheLimitArePruned() {
        let backups = names(mine, count: 12)
        let surplus = BackupRetention.surplus(in: backups, ownedBy: mine, keeping: 10) { $0 }
        #expect(surplus == Array(backups.suffix(2)))
    }

    @Test func keepingOneLeavesOnlyTheNewest() {
        let backups = names(mine, count: 4)
        let surplus = BackupRetention.surplus(in: backups, ownedBy: mine, keeping: 1) { $0 }
        #expect(surplus == Array(backups.dropFirst()))
    }

    @Test func nothingIsPrunedWhenUnderTheLimit() {
        #expect(BackupRetention.surplus(in: names(mine, count: 3), ownedBy: mine, keeping: 10) { $0 }.isEmpty)
        #expect(BackupRetention.surplus(in: [String](), ownedBy: mine, keeping: 10) { $0 }.isEmpty)
    }

    @Test func aZeroOrNegativeLimitIsClampedAndNeverSweepsEverything() {
        // The whole point of the minimum: a corrupt preference must not be able
        // to leave the user with no backup at all.
        let backups = names(mine, count: 5)
        for broken in [0, -1, Int.min] {
            let surplus = BackupRetention.surplus(in: backups, ownedBy: mine, keeping: broken) { $0 }
            #expect(surplus.count == backups.count - 1, "keeping \(broken) should behave as keeping 1")
            #expect(!surplus.contains(backups[0]), "the newest backup must always survive")
        }
    }

    // MARK: - Ownership: the reason this design exists

    @Test func onlyThisDevicesOwnBackupsArePruned() {
        // A shared iCloud folder: some of mine, some from the other phone, and
        // some written before tagging existed.
        let ours = names(mine, count: 4, startHour: 20)
        let others = names(theirs, count: 4, startHour: 16)
        let legacy = names(nil, count: 4, startHour: 12)
        let all = ours + others + legacy

        let surplus = BackupRetention.surplus(in: all, ownedBy: mine, keeping: 1) { $0 }

        #expect(surplus == Array(ours.dropFirst()),
                "only this device's backups, beyond the newest, may be removed")
        #expect(!surplus.contains { others.contains($0) }, "another device's backups are untouchable")
        #expect(!surplus.contains { legacy.contains($0) }, "untagged backups are untouchable")
    }

    @Test func aDeviceWithNoBackupsOfItsOwnPrunesNothing() {
        let all = names(theirs, count: 6) + names(nil, count: 6)
        #expect(BackupRetention.surplus(in: all, ownedBy: mine, keeping: 1) { $0 }.isEmpty)
    }

    @Test func theRuleDropsByPositionNotByRereadingDates() {
        // It trusts `listBackups()` to have sorted newest-first. Feeding it a
        // deliberately jumbled order pins that contract: whatever comes last is
        // what gets dropped.
        let backups = ["ScoreCard-Backup-2026-01-01-000000-\(mine).json",
                       "ScoreCard-Backup-2026-12-31-000000-\(mine).json",
                       "ScoreCard-Backup-2026-06-15-000000-\(mine).json"]
        let surplus = BackupRetention.surplus(in: backups, ownedBy: mine, keeping: 2) { $0 }
        #expect(surplus == [backups[2]])
    }

    // MARK: - Stored settings

    @Test func theStoredCountIsClampedIntoRange() {
        let defaults = UserDefaults(suiteName: "retention-\(UUID().uuidString)")!
        #expect(BackupRetention.storedCount(in: defaults) == BackupRetention.defaultKept)

        defaults.set(0, forKey: BackupRetention.countKey)
        #expect(BackupRetention.storedCount(in: defaults) == BackupRetention.defaultKept)

        defaults.set(999, forKey: BackupRetention.countKey)
        #expect(BackupRetention.storedCount(in: defaults) == BackupRetention.maximumKept)

        defaults.set(3, forKey: BackupRetention.countKey)
        #expect(BackupRetention.storedCount(in: defaults) == 3)
    }

    @Test func theDeviceTagIsMintedOnceAndThenReused() {
        let defaults = UserDefaults(suiteName: "retention-\(UUID().uuidString)")!
        let first = BackupRetention.deviceTag(in: defaults)
        #expect(!first.isEmpty)
        #expect(BackupRetention.deviceTag(in: defaults) == first, "the tag must be stable for the device")
    }
}
