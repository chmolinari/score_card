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

// v2 -> v3: added games.playedDateOnly, recording whether a game's played-on
// stamp has a meaningful time of day. Nullable and additive — existing rows read
// as null, which the display layer treats as "infer it the old way".
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // No DEFAULT clause: the entity declares none, so Room's expected
        // schema has no default and SQLite would otherwise record one and fail
        // validation on upgrade. A nullable column added this way is NULL for
        // every existing row, which is exactly the "predates the column" case.
        db.execSQL("ALTER TABLE `games` ADD COLUMN `playedDateOnly` INTEGER")
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
    version = 3,
    exportSchema = true,
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
