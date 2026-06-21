// File I/O for manual backups. Android has no iCloud Drive, so backups go to
// the app's external files dir (user-reachable via USB/Files on most devices,
// and included in device-to-device transfers), falling back to internal
// storage when external storage is unavailable. The filename prefix and .json
// extension match the iOS app exactly so backup files interchange between
// platforms via the document picker / share sheet.

package com.christianmolinari.scorecard.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A backup file discovered on disk, for the in-app list.
data class BackupFile(
    val file: File,
    val name: String,
    val date: Instant,
    val sizeBytes: Long,
)

class BackupStorage(private val context: Context) {

    companion object {
        // Backups are plain JSON; the .json extension means the system
        // document picker can show them with no custom type registration.
        const val FILE_EXTENSION = "json"
        const val FILE_PREFIX = "ScoreCard-Backup-"
    }

    // The backups folder, created on demand.
    val backupsDir: File
        get() {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "Backups").apply { mkdirs() }
        }

    // A timestamped backup file name, e.g. "ScoreCard-Backup-2026-06-01-183000.json".
    // Local time, like the iOS DateFormatter, so the name reads naturally to the user.
    fun makeFilename(date: Instant = Instant.now()): String {
        val formatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd-HHmmss", Locale.US)
            .withZone(ZoneId.systemDefault())
        return "$FILE_PREFIX${formatter.format(date)}.$FILE_EXTENSION"
    }

    // Write backup JSON, atomically (write a temp file, then rename) so a
    // crash mid-write can't leave a truncated backup behind.
    suspend fun write(json: String, filename: String = makeFilename()): File =
        withContext(Dispatchers.IO) {
            val file = File(backupsDir, filename)
            val temp = File(backupsDir, "$filename.tmp")
            temp.writeText(json)
            if (!temp.renameTo(file)) {
                // Rename can fail across some filesystems; fall back to a direct write.
                file.writeText(json)
                temp.delete()
            }
            file
        }

    // All backups in the backups folder, newest first.
    suspend fun listBackups(): List<BackupFile> =
        withContext(Dispatchers.IO) {
            backupsDir.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.extension == FILE_EXTENSION }
                .map { BackupFile(file = it, name = it.name, date = Instant.ofEpochMilli(it.lastModified()), sizeBytes = it.length()) }
                .sortedByDescending { it.date }
        }

    suspend fun delete(file: File) {
        withContext(Dispatchers.IO) { file.delete() }
    }

    // Read a backup picked via the Storage Access Framework (the open-document
    // contract grants read access to the returned uri, so no extra permission
    // dance is needed — the Android counterpart of the iOS security scope).
    suspend fun read(uri: Uri): String =
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IOException("Could not open backup file.")
        }

    suspend fun read(file: File): String =
        withContext(Dispatchers.IO) { file.readText() }

    // A content uri other apps can read, for the share sheet.
    fun shareUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
