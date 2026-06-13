package com.christianmolinari.scorecard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

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
    ],
    version = 1,
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
            Room.databaseBuilder(context, ScoreCardDatabase::class.java, "scorecard.db").build()
    }
}
