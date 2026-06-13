package com.christianmolinari.scorecard

import android.app.Application
import android.content.Context
import com.christianmolinari.scorecard.data.Prefs
import com.christianmolinari.scorecard.data.backup.BackupService
import com.christianmolinari.scorecard.data.backup.BackupStorage
import com.christianmolinari.scorecard.data.db.ScoreCardDatabase
import com.christianmolinari.scorecard.location.LocationCapture

// Manual dependency injection (no Hilt/Dagger/Koin): one shared instance of
// each service for the whole app. This mirrors the iOS app, which keeps a
// single SwiftData ModelContainer alive in a stored property and injects one
// shared LocationManager via the environment.
class AppContainer(context: Context) {
    val database: ScoreCardDatabase = ScoreCardDatabase.build(context)
    val prefs: Prefs = Prefs(context)
    val backupService: BackupService = BackupService(database)
    val backupStorage: BackupStorage = BackupStorage(context)
    val locationCapture: LocationCapture = LocationCapture(context)
}

class ScoreCardApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
