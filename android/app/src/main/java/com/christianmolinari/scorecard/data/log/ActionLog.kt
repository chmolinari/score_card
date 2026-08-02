package com.christianmolinari.scorecard.data.log

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// An on-device audit trail: one line per action that changes stored state, so
// an incident can be reconstructed afterwards instead of inferred from backup
// diffs. Format and rolling behaviour are specified in `docs/action-log.md`
// and mirrored by the iOS port (Services/ActionLog.swift).
//
// Three things about where this lives are deliberate:
//
//  * The log is a file, not a Room table. A reset wipes the database, so a
//    logged-as-a-table trail would be destroyed by the very reset it most
//    needs to record.
//  * It sits in the app's private files directory, so it is not visible to
//    other apps and does not appear in shared storage.
//  * It is not part of BackupSnapshot; the backup format version is unchanged.

// One recorded action. Field names and order match the iOS ActionLogEntry so
// the two platforms' logs are directly comparable.
@Serializable
data class ActionLogEntry(
    @SerialName("ts") val timestamp: String,
    // Verb naming what happened, e.g. "playerDeleted", "scoreAdded".
    val action: String,
    // Model type the action concerns, e.g. "Player", "ScoreEntry".
    val entity: String,
    // Stable-enough identifier for correlating lines about the same object.
    val entityId: String,
    // The game this action belongs to, when it belongs to one at all. Player
    // and team actions have none — correlate those by entityId/name.
    val gameId: String? = null,
    // Display name at the time of the action, so a line still reads properly
    // after the object it describes is gone.
    val name: String? = null,
    // Small free-form extras: points scored, teams affected, a clamped total.
    val detail: Map<String, String>? = null,
)

// Sizes offered in Settings. Stored as a plain Int of MiB, under the same
// preference keys the iOS app uses.
object ActionLogSize {
    val choices = listOf(10, 50, 100, 250, 500)
    const val DEFAULT_MIB = 100
    const val ENABLED_KEY = "actionLogEnabled"
    const val MAX_MIB_KEY = "actionLogMaxMiB"
}

// Appends entries to a rolling pair of files and keeps the total under the
// configured cap.
//
// Rolling uses two segments rather than trimming one file: dropping the head of
// a 100 MiB file means rewriting it, which is far too expensive on a write
// path. `actions.jsonl` is the live segment and `actions.1.jsonl` the previous
// one; each is capped at half the maximum, so the pair never exceeds it.
class ActionLog(private val directory: File) {

    private val json = Json { encodeDefaults = false; explicitNulls = false }
    private val lock = Any()

    private val stamp: DateTimeFormatter =
        // Seconds precision, no fractional part — Swift's .iso8601 decoder
        // rejects fractional seconds, and the two logs must stay comparable.
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    val currentFile: File get() = File(directory, "actions.jsonl")
    val previousFile: File get() = File(directory, "actions.1.jsonl")

    fun now(): String = stamp.format(Instant.now())

    // Append an entry. Never throws: a failure to log must not take down the
    // action that was being recorded.
    fun append(entry: ActionLogEntry, maxMiB: Int = ActionLogSize.DEFAULT_MIB) {
        synchronized(lock) {
            try {
                directory.mkdirs()
                currentFile.appendText(json.encodeToString(ActionLogEntry.serializer(), entry) + "\n")
            } catch (_: Exception) {
                return   // logging is best effort, by design
            }
            rollIfNeeded(maxMiB)
        }
    }

    private fun segmentLimit(maxMiB: Int): Long = maxOf(1, maxMiB).toLong() * 1024 * 1024 / 2

    // Rotate once the live segment fills half the budget, so the pair stays
    // within the maximum the user chose.
    private fun rollIfNeeded(maxMiB: Int) {
        if (currentFile.length() < segmentLimit(maxMiB)) return
        previousFile.delete()
        currentFile.renameTo(previousFile)
    }

    // Applies a newly lowered maximum straight away rather than waiting for the
    // next write, which might be days later.
    fun enforceLimit(maxMiB: Int) {
        synchronized(lock) {
            val limit = segmentLimit(maxMiB)
            if (previousFile.length() > limit) previousFile.delete()
            if (currentFile.length() >= limit) {
                previousFile.delete()
                currentFile.renameTo(previousFile)
            }
        }
    }

    // Bytes currently on disk across both segments.
    fun totalBytes(): Long = synchronized(lock) { currentFile.length() + previousFile.length() }

    // The most recent entries, newest first. Only the live segment is read and
    // only its tail is decoded, so opening the viewer stays instant even
    // against a full log.
    fun recentEntries(limit: Int = 500): List<ActionLogEntry> = synchronized(lock) {
        if (!currentFile.exists()) return emptyList()
        return try {
            currentFile.readLines()
                .takeLast(limit)
                .asReversed()
                // A truncated or corrupt line is skipped rather than stopping
                // the rest of the log from being read.
                .mapNotNull { line ->
                    runCatching { json.decodeFromString(ActionLogEntry.serializer(), line) }.getOrNull()
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Remove both segments. Settings only offers this while logging is off, so
    // a delete can't race a live write.
    fun deleteAll() {
        synchronized(lock) {
            currentFile.delete()
            previousFile.delete()
        }
    }

    // Both segments oldest-first, for sharing off the device.
    fun shareableFiles(): List<File> =
        synchronized(lock) { listOf(previousFile, currentFile).filter { it.exists() } }
}
