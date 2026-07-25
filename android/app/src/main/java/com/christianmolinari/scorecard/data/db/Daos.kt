package com.christianmolinari.scorecard.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerDao {
    @Query("SELECT * FROM players") fun observeAll(): Flow<List<PlayerEntity>>
    @Query("SELECT * FROM players ORDER BY createdAt") suspend fun getAll(): List<PlayerEntity>
    @Insert suspend fun insert(player: PlayerEntity): Long
    @Update suspend fun update(player: PlayerEntity)
    @Delete suspend fun delete(player: PlayerEntity)
}

@Dao
interface TeamDao {
    @Transaction @Query("SELECT * FROM teams") fun observeAllWithMembers(): Flow<List<TeamWithMembers>>
    @Transaction @Query("SELECT * FROM teams ORDER BY createdAt") suspend fun getAllWithMembers(): List<TeamWithMembers>
    @Insert suspend fun insert(team: TeamEntity): Long
    @Update suspend fun update(team: TeamEntity)
    @Delete suspend fun delete(team: TeamEntity)
    @Query("DELETE FROM team_members WHERE teamId = :teamId") suspend fun clearMembers(teamId: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMembers(refs: List<TeamMemberCrossRef>)

    @Transaction
    suspend fun setMembers(teamId: Long, playerIds: List<Long>) {
        clearMembers(teamId)
        insertMembers(playerIds.map { TeamMemberCrossRef(teamId, it) })
    }
}

@Dao
interface GameNameDao {
    @Query("SELECT * FROM game_names ORDER BY name") fun observeAll(): Flow<List<GameNameEntity>>
    @Query("SELECT * FROM game_names ORDER BY name") suspend fun getAll(): List<GameNameEntity>
    @Insert suspend fun insert(name: GameNameEntity): Long
    @Update suspend fun update(name: GameNameEntity)
    @Delete suspend fun delete(name: GameNameEntity)
}

@Dao
interface GameDao {
    @Transaction @Query("SELECT * FROM games ORDER BY createdAt DESC") fun observeAllWithDetails(): Flow<List<GameWithDetails>>
    @Transaction @Query("SELECT * FROM games WHERE id = :id") fun observeGame(id: Long): Flow<GameWithDetails?>
    @Transaction @Query("SELECT * FROM games ORDER BY createdAt") suspend fun getAllWithDetails(): List<GameWithDetails>
    @Insert suspend fun insertGame(game: GameEntity): Long
    @Update suspend fun updateGame(game: GameEntity)
    @Delete suspend fun deleteGame(game: GameEntity)
    @Insert suspend fun insertParticipant(participant: ParticipantEntity): Long
    @Insert suspend fun insertScoreEntry(entry: ScoreEntryEntity): Long
    @Delete suspend fun deleteScoreEntry(entry: ScoreEntryEntity)
    @Insert suspend fun insertSeat(seat: SeatEntity): Long
    @Query("DELETE FROM seats WHERE gameId = :gameId") suspend fun deleteSeatsForGame(gameId: Long)
    @Insert suspend fun insertGameEdit(edit: GameEditEntity): Long

    // Correcting a closed game: the delta entries and the edit record have to
    // land together, or a reader could catch a game whose scores moved with no
    // reason logged (or the reverse).
    @Transaction
    suspend fun applyScoreEdit(entries: List<ScoreEntryEntity>, edit: GameEditEntity) {
        for (entry in entries) insertScoreEntry(entry)
        insertGameEdit(edit)
    }
}

// Children before parents, mirroring BackupService.eraseAll on iOS.
@Dao
interface WipeDao {
    @Query("DELETE FROM score_entries") suspend fun wipeScoreEntries()
    @Query("DELETE FROM seats") suspend fun wipeSeats()
    @Query("DELETE FROM game_edits") suspend fun wipeGameEdits()
    @Query("DELETE FROM participants") suspend fun wipeParticipants()
    @Query("DELETE FROM games") suspend fun wipeGames()
    @Query("DELETE FROM team_members") suspend fun wipeTeamMembers()
    @Query("DELETE FROM teams") suspend fun wipeTeams()
    @Query("DELETE FROM players") suspend fun wipePlayers()
    @Query("DELETE FROM game_names") suspend fun wipeGameNames()

    @Transaction
    suspend fun wipeAll() {
        wipeScoreEntries()
        wipeSeats()
        wipeGameEdits()
        wipeParticipants()
        wipeGames()
        wipeTeamMembers()
        wipeTeams()
        wipePlayers()
        wipeGameNames()
    }
}
