package com.christianmolinari.scorecard

import com.christianmolinari.scorecard.domain.BackupRetention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Backup retention, and above all *whose* backups a device may delete. On iOS
// the backup folder is shared between the user's devices, so the ownership rule
// is what stops one phone quietly removing another's history; the convention is
// identical here because the files interchange. Kept in step with the iOS
// BackupRetentionTests.
class BackupRetentionTest {

    private val mine = "a3f19c"
    private val theirs = "b7e402"

    // Names as BackupStorage.makeFilename writes them, newest first.
    private fun names(tag: String?, count: Int, startHour: Int = 12): List<String> =
        (0 until count).map { index ->
            val stamp = "2026-08-02-%02d0000".format(startHour - index)
            if (tag != null) "ScoreCard-Backup-$stamp-$tag.json" else "ScoreCard-Backup-$stamp.json"
        }

    // MARK: tag parsing

    @Test
    fun aTagIsReadBackFromTheFilename() {
        assertEquals(mine, BackupRetention.deviceTag("ScoreCard-Backup-2026-08-02-181500-$mine.json"))
    }

    @Test
    fun aBackupWrittenBeforeTaggingHasNoTag() {
        assertNull(BackupRetention.deviceTag("ScoreCard-Backup-2026-07-25-191352.json"))
    }

    @Test
    fun tagParsingIsNotFooledByOtherNames() {
        assertNull(BackupRetention.deviceTag("Something-Else-2026-08-02-120000-$mine.json"))
        assertNull(BackupRetention.deviceTag("ScoreCard-Backup-2026-08-02-120000-$mine.txt"))
        assertNull(BackupRetention.deviceTag("ScoreCard-Backup-2026-08.json"))
        // A trailing hyphen leaves an empty tag, which is no tag at all.
        assertNull(BackupRetention.deviceTag("ScoreCard-Backup-2026-08-02-120000-.json"))
        // A tag may itself contain hyphens; everything after the stamp is the tag.
        assertEquals("a3-f1", BackupRetention.deviceTag("ScoreCard-Backup-2026-08-02-120000-a3-f1.json"))
    }

    // MARK: the retention rule

    @Test
    fun onlyTheOldestBeyondTheLimitArePruned() {
        val backups = names(mine, 12)
        assertEquals(backups.takeLast(2), BackupRetention.surplus(backups, mine, 10) { it })
    }

    @Test
    fun keepingOneLeavesOnlyTheNewest() {
        val backups = names(mine, 4)
        assertEquals(backups.drop(1), BackupRetention.surplus(backups, mine, 1) { it })
    }

    @Test
    fun nothingIsPrunedWhenUnderTheLimit() {
        assertTrue(BackupRetention.surplus(names(mine, 3), mine, 10) { it }.isEmpty())
        assertTrue(BackupRetention.surplus(emptyList<String>(), mine, 10) { it }.isEmpty())
    }

    @Test
    fun aZeroOrNegativeLimitIsClampedAndNeverSweepsEverything() {
        // The whole point of the minimum: a corrupt preference must not be able
        // to leave the user with no backup at all.
        val backups = names(mine, 5)
        for (broken in listOf(0, -1, Int.MIN_VALUE)) {
            val surplus = BackupRetention.surplus(backups, mine, broken) { it }
            assertEquals("keeping $broken should behave as keeping 1", backups.size - 1, surplus.size)
            assertFalse("the newest backup must always survive", surplus.contains(backups[0]))
        }
    }

    // MARK: ownership — the reason this design exists

    @Test
    fun onlyThisDevicesOwnBackupsArePruned() {
        val ours = names(mine, 4, startHour = 20)
        val others = names(theirs, 4, startHour = 16)
        val legacy = names(null, 4, startHour = 12)

        val surplus = BackupRetention.surplus(ours + others + legacy, mine, 1) { it }

        assertEquals(ours.drop(1), surplus)
        assertTrue("another device's backups are untouchable", surplus.none { it in others })
        assertTrue("untagged backups are untouchable", surplus.none { it in legacy })
    }

    @Test
    fun aDeviceWithNoBackupsOfItsOwnPrunesNothing() {
        val all = names(theirs, 6) + names(null, 6)
        assertTrue(BackupRetention.surplus(all, mine, 1) { it }.isEmpty())
    }

    @Test
    fun theRuleDropsByPositionNotByRereadingDates() {
        // It trusts listBackups() to have sorted newest-first. A deliberately
        // jumbled order pins that contract.
        val backups = listOf(
            "ScoreCard-Backup-2026-01-01-000000-$mine.json",
            "ScoreCard-Backup-2026-12-31-000000-$mine.json",
            "ScoreCard-Backup-2026-06-15-000000-$mine.json",
        )
        assertEquals(listOf(backups[2]), BackupRetention.surplus(backups, mine, 2) { it })
    }

    // MARK: stored settings

    @Test
    fun theStoredCountIsClampedIntoRange() {
        assertEquals(BackupRetention.DEFAULT_KEPT, BackupRetention.clampCount(null))
        assertEquals(BackupRetention.DEFAULT_KEPT, BackupRetention.clampCount(0))
        assertEquals(BackupRetention.DEFAULT_KEPT, BackupRetention.clampCount(-5))
        assertEquals(BackupRetention.MAXIMUM_KEPT, BackupRetention.clampCount(999))
        assertEquals(3, BackupRetention.clampCount(3))
        assertEquals(BackupRetention.MINIMUM_KEPT, BackupRetention.clampCount(1))
    }

    @Test
    fun theDefaultsMatchTheAgreedBehaviour() {
        assertEquals(10, BackupRetention.DEFAULT_KEPT)
        assertEquals(1, BackupRetention.MINIMUM_KEPT)
    }
}
