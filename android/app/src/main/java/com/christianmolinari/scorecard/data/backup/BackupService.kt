// Converts the Room store to/from a portable BackupSnapshot, and erases the
// whole store. The (slow, environment-dependent) file I/O lives separately in
// BackupStorage.

package com.christianmolinari.scorecard.data.backup

import androidx.room.withTransaction
import com.christianmolinari.scorecard.data.db.GameEditEntity
import com.christianmolinari.scorecard.data.db.GameEntity
import com.christianmolinari.scorecard.data.db.GameNameEntity
import com.christianmolinari.scorecard.data.db.ParticipantEntity
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.ScoreCardDatabase
import com.christianmolinari.scorecard.data.db.ScoreEntryEntity
import com.christianmolinari.scorecard.data.db.SeatEntity
import com.christianmolinari.scorecard.data.db.TeamEntity
import com.christianmolinari.scorecard.data.db.TeamMemberCrossRef
import com.christianmolinari.scorecard.domain.displayName
import com.christianmolinari.scorecard.domain.sortedEdits
import java.time.Instant

class BackupService(private val database: ScoreCardDatabase) {

    // Serialize the whole store to a snapshot. Players/teams/games are listed
    // in createdAt order and game names in name order — the same orders the
    // iOS exporter uses — so relationship indices mean the same thing on both
    // platforms (and a re-export is stable).
    suspend fun makeSnapshot(): BackupSnapshot {
        val players = database.playerDao().getAll()
        val teams = database.teamDao().getAllWithMembers()
        val games = database.gameDao().getAllWithDetails()
        val gameNames = database.gameNameDao().getAll()

        val playerIndex = players.withIndex().associate { (i, player) -> player.id to i }
        val teamIndex = teams.withIndex().associate { (i, team) -> team.team.id to i }

        return BackupSnapshot(
            exportedAt = Instant.now(),
            players = players.map { PlayerDTO(name = it.name, createdAt = it.createdAt) },
            teams = teams.map { team ->
                TeamDTO(
                    name = team.team.name,
                    createdAt = team.team.createdAt,
                    memberIndices = team.members.mapNotNull { playerIndex[it.id] },
                )
            },
            games = games.map { game ->
                GameDTO(
                    title = game.game.title,
                    hasTarget = game.game.hasTarget,
                    targetPoints = game.game.targetPoints,
                    createdAt = game.game.createdAt,
                    closedAt = game.game.closedAt,
                    latitude = game.game.latitude,
                    longitude = game.game.longitude,
                    locationName = game.game.locationName,
                    participants = game.participants
                        .sortedBy { it.participant.sortIndex }
                        .map { participant ->
                            ParticipantDTO(
                                // The live display name, not the stored snapshot,
                                // so the backup carries any rename made since.
                                nameSnapshot = participant.displayName,
                                sortIndex = participant.participant.sortIndex,
                                playerIndex = participant.participant.playerId?.let { playerIndex[it] },
                                teamIndex = participant.participant.teamId?.let { teamIndex[it] },
                                entries = participant.entries
                                    .sortedBy { it.timestamp }
                                    .map { EntryDTO(points = it.points, timestamp = it.timestamp) },
                            )
                        },
                    seats = game.seats
                        .sortedBy { it.seat.position }
                        .map { seat ->
                            SeatDTO(
                                position = seat.seat.position,
                                playerIndex = seat.seat.playerId?.let { playerIndex[it] },
                            )
                        },
                    currentDealerIndex = game.game.currentDealerIndex,
                    currentHand = game.game.currentHand,
                    // Newest first, matching what iOS writes from sortedEdits.
                    edits = game.sortedEdits.map {
                        GameEditDTO(reason = it.reason, editedAt = it.editedAt)
                    },
                )
            },
            gameNames = gameNames.map {
                GameNameDTO(name = it.name, createdAt = it.createdAt, lastUsedAt = it.lastUsedAt)
            },
        )
    }

    suspend fun exportJson(): String = BackupCodec.encode(makeSnapshot())

    // Decode backup `text` and replace the entire store with its contents.
    // Returns the snapshot so callers can report what was restored. The wipe
    // and all the inserts run in one transaction, so a malformed-but-decodable
    // backup can't leave the store half-restored.
    suspend fun restore(text: String): BackupSnapshot {
        val snapshot = BackupCodec.decode(text)
        database.withTransaction {
            database.wipeDao().wipeAll()

            for (dto in snapshot.gameNames.orEmpty()) {
                database.gameNameDao().insert(
                    GameNameEntity(name = dto.name, createdAt = dto.createdAt, lastUsedAt = dto.lastUsedAt)
                )
            }

            val playerIds = snapshot.players.map { dto ->
                database.playerDao().insert(PlayerEntity(name = dto.name, createdAt = dto.createdAt))
            }

            val teamIds = snapshot.teams.map { dto ->
                val teamId = database.teamDao().insert(TeamEntity(name = dto.name, createdAt = dto.createdAt))
                database.teamDao().insertMembers(
                    // Out-of-range indices are silently dropped, like iOS.
                    dto.memberIndices.mapNotNull { playerIds.getOrNull(it) }
                        .map { TeamMemberCrossRef(teamId = teamId, playerId = it) }
                )
                teamId
            }

            for (dto in snapshot.games) {
                val gameId = database.gameDao().insertGame(
                    GameEntity(
                        title = dto.title,
                        hasTarget = dto.hasTarget,
                        targetPoints = dto.targetPoints,
                        createdAt = dto.createdAt,
                        closedAt = dto.closedAt,
                        latitude = dto.latitude,
                        longitude = dto.longitude,
                        locationName = dto.locationName,
                        currentDealerIndex = dto.currentDealerIndex ?: 0,
                        currentHand = dto.currentHand ?: 1,
                    )
                )

                for (pdto in dto.participants) {
                    // Resolve the player link first, then the team link; an
                    // out-of-range index leaves the participant unlinked and
                    // the nameSnapshot carries the history (like iOS).
                    val playerId = pdto.playerIndex?.let { playerIds.getOrNull(it) }
                    val teamId = if (playerId == null) pdto.teamIndex?.let { teamIds.getOrNull(it) } else null
                    val participantId = database.gameDao().insertParticipant(
                        ParticipantEntity(
                            gameId = gameId,
                            playerId = playerId,
                            teamId = teamId,
                            nameSnapshot = pdto.nameSnapshot,
                            sortIndex = pdto.sortIndex,
                        )
                    )
                    for (edto in pdto.entries) {
                        database.gameDao().insertScoreEntry(
                            ScoreEntryEntity(
                                participantId = participantId,
                                points = edto.points,
                                timestamp = edto.timestamp,
                            )
                        )
                    }
                }

                for (sdto in dto.seats.orEmpty()) {
                    database.gameDao().insertSeat(
                        SeatEntity(
                            gameId = gameId,
                            playerId = sdto.playerIndex?.let { playerIds.getOrNull(it) },
                            position = sdto.position,
                        )
                    )
                }

                for (edto in dto.edits.orEmpty()) {
                    database.gameDao().insertGameEdit(
                        GameEditEntity(
                            gameId = gameId,
                            reason = edto.reason,
                            editedAt = edto.editedAt,
                        )
                    )
                }
            }
        }
        return snapshot
    }

    // Delete every record in the store. WipeDao deletes children before
    // parents so foreign keys never dangle mid-wipe; Room flows refresh
    // automatically, so the UI updates immediately (the concern the iOS
    // per-object delete workaround addresses doesn't exist here).
    suspend fun eraseAll() {
        database.wipeDao().wipeAll()
    }
}
