package com.christianmolinari.scorecard.data.log

import com.christianmolinari.scorecard.data.db.GameDao
import com.christianmolinari.scorecard.data.db.GameEditEntity
import com.christianmolinari.scorecard.data.db.GameEntity
import com.christianmolinari.scorecard.data.db.GameNameDao
import com.christianmolinari.scorecard.data.db.GameNameEntity
import com.christianmolinari.scorecard.data.db.GameWithDetails
import com.christianmolinari.scorecard.data.db.ParticipantEntity
import com.christianmolinari.scorecard.data.db.PlayerDao
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.ScoreEntryEntity
import com.christianmolinari.scorecard.data.db.SeatEntity
import com.christianmolinari.scorecard.data.db.TeamDao
import com.christianmolinari.scorecard.data.db.TeamEntity
import com.christianmolinari.scorecard.data.db.TeamMemberCrossRef
import com.christianmolinari.scorecard.data.db.TeamWithMembers
import com.christianmolinari.scorecard.data.db.WipeDao
import kotlinx.coroutines.flow.Flow

// Room has no row-level change hook — InvalidationTracker reports which tables
// changed, not what happened to which row — so where iOS gets away with a
// single ModelContext.willSave observer, Android records at the DAO boundary.
//
// Each wrapper delegates verbatim and logs the writes. Reads pass straight
// through unlogged: they change nothing, and a Flow that re-emits on every
// database touch would bury the log in noise.
//
// AppContainer hands these out in place of the raw DAOs, so screens get logging
// without any of them knowing about it.

// Reads the two preferences on the write path. They are plain values rather
// than a Flow because a DAO call needs the answer synchronously, and a stale
// read only ever costs one line either way.
class ActionLogSettings(
    val isEnabled: () -> Boolean,
    val maxMiB: () -> Int,
)

private fun ActionLog.record(
    settings: ActionLogSettings,
    action: String,
    entity: String,
    entityId: Any?,
    gameId: Any? = null,
    name: String? = null,
    detail: Map<String, String>? = null,
) {
    if (!settings.isEnabled()) return
    append(
        ActionLogEntry(
            timestamp = now(),
            action = action,
            entity = entity,
            entityId = entityId?.toString() ?: "-",
            gameId = gameId?.toString(),
            name = name,
            detail = detail,
        ),
        maxMiB = settings.maxMiB(),
    )
}

class LoggingPlayerDao(
    private val delegate: PlayerDao,
    private val log: ActionLog,
    private val settings: ActionLogSettings,
    // Supplied by AppContainer, which can reach the team DAO. A player row
    // knows nothing about its teams, and after the delete they are unreachable.
    private val teamNamesForPlayer: suspend (Long) -> List<String>,
) : PlayerDao {
    override fun observeAll(): Flow<List<PlayerEntity>> = delegate.observeAll()
    override suspend fun getAll(): List<PlayerEntity> = delegate.getAll()

    override suspend fun insert(player: PlayerEntity): Long {
        val id = delegate.insert(player)
        log.record(settings, "playerCreated", "Player", id, name = player.name)
        return id
    }

    override suspend fun update(player: PlayerEntity) {
        delegate.update(player)
        log.record(settings, "playerChanged", "Player", player.id, name = player.name)
    }

    override suspend fun delete(player: PlayerEntity) {
        // Read before the delete: the membership rows cascade away with the
        // player, so afterwards this detail is unrecoverable — and it is
        // exactly what was missing when two players vanished on 25 July.
        val teams = runCatching { teamNamesForPlayer(player.id) }.getOrDefault(emptyList())
        log.record(settings, "playerDeleted", "Player", player.id, name = player.name,
            detail = if (teams.isEmpty()) null else mapOf("teams" to teams.joinToString(", ")))
        delegate.delete(player)
    }
}

class LoggingTeamDao(
    private val delegate: TeamDao,
    private val log: ActionLog,
    private val settings: ActionLogSettings,
) : TeamDao {
    override fun observeAllWithMembers(): Flow<List<TeamWithMembers>> = delegate.observeAllWithMembers()
    override suspend fun getAllWithMembers(): List<TeamWithMembers> = delegate.getAllWithMembers()

    override suspend fun insert(team: TeamEntity): Long {
        val id = delegate.insert(team)
        log.record(settings, "teamCreated", "Team", id, name = team.name)
        return id
    }

    override suspend fun update(team: TeamEntity) {
        delegate.update(team)
        log.record(settings, "teamChanged", "Team", team.id, name = team.name)
    }

    override suspend fun delete(team: TeamEntity) {
        log.record(settings, "teamDeleted", "Team", team.id, name = team.name)
        delegate.delete(team)
    }

    override suspend fun clearMembers(teamId: Long) = delegate.clearMembers(teamId)

    override suspend fun insertMembers(refs: List<TeamMemberCrossRef>) {
        delegate.insertMembers(refs)
        if (refs.isNotEmpty()) {
            log.record(settings, "teamMembersChanged", "Team", refs.first().teamId,
                detail = mapOf("playerIds" to refs.joinToString(", ") { it.playerId.toString() }))
        }
    }
}

class LoggingGameNameDao(
    private val delegate: GameNameDao,
    private val log: ActionLog,
    private val settings: ActionLogSettings,
) : GameNameDao {
    override fun observeAll(): Flow<List<GameNameEntity>> = delegate.observeAll()
    override suspend fun getAll(): List<GameNameEntity> = delegate.getAll()

    override suspend fun insert(name: GameNameEntity): Long {
        val id = delegate.insert(name)
        log.record(settings, "gameNameCreated", "GameName", id, name = name.name)
        return id
    }

    override suspend fun update(name: GameNameEntity) {
        delegate.update(name)
        log.record(settings, "gameNameChanged", "GameName", name.id, name = name.name)
    }

    override suspend fun delete(name: GameNameEntity) {
        log.record(settings, "gameNameDeleted", "GameName", name.id, name = name.name)
        delegate.delete(name)
    }
}

// Every scoring-altering action passes through here: points added, points
// undone, a hand advanced, a game closed, and a finished game corrected.
class LoggingGameDao(
    private val delegate: GameDao,
    private val log: ActionLog,
    private val settings: ActionLogSettings,
) : GameDao {
    override fun observeAllWithDetails(): Flow<List<GameWithDetails>> = delegate.observeAllWithDetails()
    override fun observeGame(id: Long): Flow<GameWithDetails?> = delegate.observeGame(id)
    override suspend fun getAllWithDetails(): List<GameWithDetails> = delegate.getAllWithDetails()

    override suspend fun insertGame(game: GameEntity): Long {
        val id = delegate.insertGame(game)
        log.record(settings, "gameCreated", "Game", id, gameId = id, name = game.title,
            detail = buildMap {
                game.targetPoints?.let { put("target", it.toString()) }
                if (game.closedAt != null) put("closed", "true")
            })
        return id
    }

    override suspend fun updateGame(game: GameEntity) {
        delegate.updateGame(game)
        // Covers closing a game, advancing the hand, and moving the dealer —
        // all of which are edits to the game row.
        log.record(settings, "gameChanged", "Game", game.id, gameId = game.id, name = game.title,
            detail = buildMap {
                put("hand", game.currentHand.toString())
                put("dealerIndex", game.currentDealerIndex.toString())
                game.closedAt?.let { put("closedAt", it.toString()) }
            })
    }

    override suspend fun deleteGame(game: GameEntity) {
        log.record(settings, "gameDeleted", "Game", game.id, gameId = game.id, name = game.title)
        delegate.deleteGame(game)
    }

    override suspend fun insertParticipant(participant: ParticipantEntity): Long {
        val id = delegate.insertParticipant(participant)
        log.record(settings, "participantCreated", "GameParticipant", id,
            gameId = participant.gameId, name = participant.nameSnapshot)
        return id
    }

    override suspend fun gameIdForParticipant(participantId: Long): Long? =
        delegate.gameIdForParticipant(participantId)

    override suspend fun insertScoreEntry(entry: ScoreEntryEntity): Long {
        val id = delegate.insertScoreEntry(entry)
        log.record(settings, "scoreAdded", "ScoreEntry", id,
            gameId = gameIdOf(entry.participantId),
            detail = mapOf("points" to entry.points.toString(),
                           "participantId" to entry.participantId.toString()))
        return id
    }

    override suspend fun deleteScoreEntry(entry: ScoreEntryEntity) {
        log.record(settings, "scoreRemoved", "ScoreEntry", entry.id,
            gameId = gameIdOf(entry.participantId),
            detail = mapOf("points" to entry.points.toString(),
                           "participantId" to entry.participantId.toString()))
        delegate.deleteScoreEntry(entry)
    }

    // Resolved per scoring line so the log can be filtered by game. Failure is
    // swallowed to a null gameId — losing the correlation is acceptable, losing
    // the point the user just scored is not.
    private suspend fun gameIdOf(participantId: Long): Long? =
        if (!settings.isEnabled()) null
        else runCatching { delegate.gameIdForParticipant(participantId) }.getOrNull()

    override suspend fun insertSeat(seat: SeatEntity): Long {
        val id = delegate.insertSeat(seat)
        log.record(settings, "seatCreated", "Seat", id, gameId = seat.gameId,
            detail = mapOf("position" to seat.position.toString()))
        return id
    }

    override suspend fun deleteSeatsForGame(gameId: Long) {
        log.record(settings, "seatsCleared", "Seat", gameId, gameId = gameId)
        delegate.deleteSeatsForGame(gameId)
    }

    override suspend fun insertGameEdit(edit: GameEditEntity): Long {
        val id = delegate.insertGameEdit(edit)
        log.record(settings, "gameEditRecorded", "GameEdit", id, gameId = edit.gameId,
            detail = mapOf("reason" to edit.reason))
        return id
    }
}

class LoggingWipeDao(
    private val delegate: WipeDao,
    private val log: ActionLog,
    private val settings: ActionLogSettings,
) : WipeDao {
    override suspend fun wipeScoreEntries() = delegate.wipeScoreEntries()
    override suspend fun wipeSeats() = delegate.wipeSeats()
    override suspend fun wipeGameEdits() = delegate.wipeGameEdits()
    override suspend fun wipeParticipants() = delegate.wipeParticipants()
    override suspend fun wipeGames() = delegate.wipeGames()
    override suspend fun wipeTeamMembers() = delegate.wipeTeamMembers()
    override suspend fun wipeTeams() = delegate.wipeTeams()
    override suspend fun wipePlayers() = delegate.wipePlayers()
    override suspend fun wipeGameNames() = delegate.wipeGameNames()

    // One line for the whole wipe rather than nine, so a reset reads as the
    // single deliberate act it is.
    override suspend fun wipeAll() {
        log.record(settings, "storeWiped", "Store", "-")
        delegate.wipeAll()
    }
}
