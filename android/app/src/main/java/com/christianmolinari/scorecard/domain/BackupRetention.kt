package com.christianmolinari.scorecard.domain

// How many backups to keep, and which ones a device is allowed to remove.
// Port of the iOS Models/BackupRetention.swift.
//
// Android backups are device-local, so the ownership half of this rule is not
// strictly needed here — but the filename convention and the pruning logic are
// kept identical to iOS on purpose. Backup files interchange between the two
// apps, so a file written on Android may well end up sitting in an iCloud
// folder next to backups from two iPhones, where "only prune what this device
// wrote" is the rule that stops one device deleting another's history.
object BackupRetention {

    // Never fewer than one: the app must not be left with no backup at all.
    const val MINIMUM_KEPT = 1
    const val DEFAULT_KEPT = 10

    // Upper bound of the Settings stepper.
    const val MAXIMUM_KEPT = 50

    const val COUNT_KEY = "backupRetentionCount"
    const val DEVICE_TAG_KEY = "backupDeviceTag"

    // Width of the "yyyy-MM-dd-HHmmss" stamp BackupStorage.makeFilename writes.
    private const val TIMESTAMP_LENGTH = 17

    // The device tag in a backup filename, or null if there isn't one.
    //
    // Names look like "ScoreCard-Backup-2026-08-02-181500-a3f19c.json": a fixed
    // prefix, a fixed-width timestamp, then the tag. Backups written before
    // tagging existed stop after the timestamp and return null — which is what
    // keeps them out of every prune.
    fun deviceTag(
        filename: String,
        prefix: String = "ScoreCard-Backup-",
        fileExtension: String = "json",
    ): String? {
        if (!filename.startsWith(prefix)) return null
        var body = filename.removePrefix(prefix)

        val suffix = ".$fileExtension"
        if (!body.endsWith(suffix)) return null
        body = body.removeSuffix(suffix)

        if (body.length <= TIMESTAMP_LENGTH) return null
        if (body[TIMESTAMP_LENGTH] != '-') return null

        return body.substring(TIMESTAMP_LENGTH + 1).ifEmpty { null }
    }

    // Whether this device wrote the backup, and may therefore remove it.
    fun isOwned(filename: String, deviceTag: String): Boolean =
        deviceTag(filename) == deviceTag

    // The backups to delete: this device's own, beyond the newest `keeping`.
    //
    // `backups` must already be newest-first — listBackups() sorts that way,
    // and the tests pin that this drops by position rather than re-reading
    // dates. `keeping` is clamped to at least one, so a corrupt or zero stored
    // preference can never sweep the folder.
    fun <T> surplus(
        backups: List<T>,
        deviceTag: String,
        keeping: Int,
        filename: (T) -> String,
    ): List<T> {
        val limit = maxOf(MINIMUM_KEPT, keeping)
        val mine = backups.filter { isOwned(filename(it), deviceTag) }
        return if (mine.size > limit) mine.drop(limit) else emptyList()
    }

    // Clamp a stored limit into range.
    fun clampCount(stored: Int?): Int = when {
        stored == null || stored <= 0 -> DEFAULT_KEPT
        else -> stored.coerceIn(MINIMUM_KEPT, MAXIMUM_KEPT)
    }
}
