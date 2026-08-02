package com.christianmolinari.scorecard

import com.christianmolinari.scorecard.data.log.ActionLog
import com.christianmolinari.scorecard.data.log.ActionLogEntry
import com.christianmolinari.scorecard.data.log.ActionLogSize
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The action log's file behaviour: the format the two platforms share, and the
// rolling that keeps it inside the size the user chose. Kept in step with the
// iOS ActionLogTests. Specified in docs/action-log.md.
class ActionLogTest {

    private lateinit var directory: File
    private lateinit var log: ActionLog

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("actionlog").toFile()
        log = ActionLog(directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun entry(action: String = "scoreAdded", gameId: String? = "7") = ActionLogEntry(
        timestamp = log.now(),
        action = action,
        entity = "ScoreEntry",
        entityId = "1",
        gameId = gameId,
        name = "Bassano",
        detail = mapOf("points" to "1"),
    )

    @Test
    fun aLineIsOneJsonObjectPerAction() {
        log.append(entry())
        log.append(entry(action = "scoreRemoved"))

        val lines = log.currentFile.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") })
        assertTrue(lines[0].contains("\"action\":\"scoreAdded\""))
        assertTrue(lines[1].contains("\"action\":\"scoreRemoved\""))
    }

    @Test
    fun timestampsAreSecondPrecisionUtc() {
        // Swift's .iso8601 decoder rejects fractional seconds, so the shared
        // format must not emit them.
        val stamp = log.now()
        assertTrue(stamp, Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(stamp))
    }

    @Test
    fun absentOptionalsAreOmittedRatherThanWrittenAsNull() {
        log.append(entry(gameId = null).copy(name = null, detail = null))
        val line = log.currentFile.readLines().single()
        assertFalse(line, line.contains("null"))
        assertFalse(line, line.contains("gameId"))
    }

    @Test
    fun theLogRollsAtHalfTheMaximumAndNeverExceedsIt() {
        // 1 MiB cap → 512 KiB per segment. Write past two segments' worth and
        // check the pair still fits inside the cap.
        val maxMiB = 1
        repeat(6000) { log.append(entry(), maxMiB = maxMiB) }

        assertTrue("expected a rolled segment", log.previousFile.exists())
        val cap = maxMiB.toLong() * 1024 * 1024
        assertTrue("total ${log.totalBytes()} exceeded cap $cap", log.totalBytes() <= cap)
    }

    @Test
    fun loweringTheMaximumTakesEffectImmediately() {
        repeat(4000) { log.append(entry(), maxMiB = 100) }
        val before = log.totalBytes()
        assertTrue(before > 0)

        // A 1 MiB cap has a 512 KiB segment limit; the live segment is over it.
        log.enforceLimit(maxMiB = 1)
        assertTrue(log.totalBytes() <= 1L * 1024 * 1024)
    }

    @Test
    fun recentEntriesComeBackNewestFirst() {
        log.append(entry(action = "first"))
        log.append(entry(action = "second"))
        log.append(entry(action = "third"))

        assertEquals(listOf("third", "second", "first"), log.recentEntries().map { it.action })
    }

    @Test
    fun aCorruptLineIsSkippedRatherThanLosingTheRest() {
        log.append(entry(action = "good"))
        log.currentFile.appendText("{ this is not json\n")
        log.append(entry(action = "alsoGood"))

        assertEquals(listOf("alsoGood", "good"), log.recentEntries().map { it.action })
    }

    @Test
    fun deletingRemovesBothSegments() {
        repeat(6000) { log.append(entry(), maxMiB = 1) }
        assertTrue(log.previousFile.exists())

        log.deleteAll()
        assertFalse(log.currentFile.exists())
        assertFalse(log.previousFile.exists())
        assertEquals(0L, log.totalBytes())
        assertTrue(log.shareableFiles().isEmpty())
    }

    @Test
    fun namesWithQuotesAndNewlinesStayOnOneLine() {
        // A player could be named anything; one entry must never become two.
        log.append(entry().copy(name = "Odd \"Name\"\nwith a newline"))
        assertEquals(1, log.currentFile.readLines().size)
        assertEquals("Odd \"Name\"\nwith a newline", log.recentEntries().single().name)
    }

    @Test
    fun defaultsAreRecordingOnAtOneHundredMiB() {
        assertEquals(100, ActionLogSize.DEFAULT_MIB)
        assertTrue(ActionLogSize.choices.contains(ActionLogSize.DEFAULT_MIB))
    }
}
