package com.christianmolinari.scorecard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// v1 -> v2: added game_edits, the log of corrections made to a closed game.
// Purely additive — no existing table is touched, so games recorded before this
// version simply have no edits and read as never edited.
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `game_edits` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`gameId` INTEGER NOT NULL, " +
                "`reason` TEXT NOT NULL, " +
                "`editedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`gameId`) REFERENCES `games`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_edits_gameId` ON `game_edits` (`gameId`)")
    }
}

@Database(
    entities = [
        PlayerEntity::class,
        TeamEntity::class,
        TeamMemberCrossRef::class,
        GameNameEntity::class,
        GameEntity::class,
        ParticipantEntity::class,
        ScoreEntryEntity::class,
        SeatEntity::class,
        GameEditEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(InstantConverters::class)
abstract class ScoreCardDatabase : RoomDatabase() {
    abstract fun playerDao(): PlayerDao
    abstract fun teamDao(): TeamDao
    abstract fun gameNameDao(): GameNameDao
    abstract fun gameDao(): GameDao
    abstract fun wipeDao(): WipeDao

    companion object {
        fun build(context: Context): ScoreCardDatabase =
            Room.databaseBuilder(context, ScoreCardDatabase::class.java, "scorecard.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
