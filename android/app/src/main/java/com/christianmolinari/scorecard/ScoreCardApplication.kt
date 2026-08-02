package com.christianmolinari.scorecard

import android.app.Application
import android.content.Context
import com.christianmolinari.scorecard.data.Prefs
import com.christianmolinari.scorecard.data.backup.BackupService
import com.christianmolinari.scorecard.data.backup.BackupStorage
import com.christianmolinari.scorecard.data.db.GameDao
import com.christianmolinari.scorecard.data.db.GameNameDao
import com.christianmolinari.scorecard.data.db.PlayerDao
import com.christianmolinari.scorecard.data.db.ScoreCardDatabase
import com.christianmolinari.scorecard.data.db.TeamDao
import com.christianmolinari.scorecard.data.db.WipeDao
import com.christianmolinari.scorecard.data.log.ActionLog
import com.christianmolinari.scorecard.data.log.ActionLogSettings
import com.christianmolinari.scorecard.data.log.ActionLogSize
import com.christianmolinari.scorecard.data.log.LoggingGameDao
import com.christianmolinari.scorecard.data.log.LoggingGameNameDao
import com.christianmolinari.scorecard.data.log.LoggingPlayerDao
import com.christianmolinari.scorecard.data.log.LoggingTeamDao
import com.christianmolinari.scorecard.data.log.LoggingWipeDao
import com.christianmolinari.scorecard.location.LocationCapture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Manual dependency injection (no Hilt/Dagger/Koin): one shared instance of
// each service for the whole app. This mirrors the iOS app, which keeps a
// single SwiftData ModelContainer alive in a stored property and injects one
// shared LocationManager via the environment.
class AppContainer(
    context: Context,
    // Injectable only so instrumented tests can drive the screens against an
    // in-memory database instead of the one holding the user's games.
    // Production never passes it and still gets the single shared database.
    val database: ScoreCardDatabase = ScoreCardDatabase.build(context),
) {
    val prefs: Prefs = Prefs(context)
    val backupStorage: BackupStorage = BackupStorage(context)
    val locationCapture: LocationCapture = LocationCapture(context)

    // The action log, and the DAOs that feed it. Screens use these in place of
    // database.<x>Dao() so every write is recorded without any screen knowing.
    val actionLog: ActionLog = ActionLog(File(context.filesDir, "logs"))

    // The write path needs the two preferences synchronously, so their latest
    // values are cached from the DataStore flows rather than collected per call.
    private val logSettings = ActionLogSettings(
        isEnabled = { cachedLogEnabled },
        maxMiB = { cachedLogMaxMiB },
    )

    @Volatile private var cachedLogEnabled: Boolean = true
    @Volatile private var cachedLogMaxMiB: Int = ActionLogSize.DEFAULT_MIB

    init {
        // One long-lived collector per preference. The scope lives as long as
        // the container, which lives as long as the process.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { prefs.actionLogEnabled.collect { cachedLogEnabled = it } }
        scope.launch { prefs.actionLogMaxMiB.collect { cachedLogMaxMiB = it } }
    }

    val playerDao: PlayerDao = LoggingPlayerDao(
        database.playerDao(), actionLog, logSettings,
        teamNamesForPlayer = { playerId ->
            database.teamDao().getAllWithMembers()
                .filter { team -> team.members.any { it.id == playerId } }
                .map { it.team.name }
        },
    )
    val teamDao: TeamDao = LoggingTeamDao(database.teamDao(), actionLog, logSettings)
    val gameNameDao: GameNameDao = LoggingGameNameDao(database.gameNameDao(), actionLog, logSettings)
    val gameDao: GameDao = LoggingGameDao(database.gameDao(), actionLog, logSettings)
    val wipeDao: WipeDao = LoggingWipeDao(database.wipeDao(), actionLog, logSettings)

    // Declared after the DAOs so a restore is recorded like any other write.
    val backupService: BackupService = BackupService(
        database, playerDao, teamDao, gameDao, gameNameDao, wipeDao,
    )
}

class ScoreCardApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
