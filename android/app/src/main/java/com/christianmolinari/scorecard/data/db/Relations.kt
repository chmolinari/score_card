package com.christianmolinari.scorecard.data.db

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class TeamWithMembers(
    @Embedded val team: TeamEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(TeamMemberCrossRef::class, parentColumn = "teamId", entityColumn = "playerId"),
    )
    val members: List<PlayerEntity>,
)

data class ParticipantWithDetails(
    @Embedded val participant: ParticipantEntity,
    @Relation(parentColumn = "id", entityColumn = "participantId")
    val entries: List<ScoreEntryEntity>,
    @Relation(parentColumn = "playerId", entityColumn = "id")
    val player: PlayerEntity?,
    @Relation(parentColumn = "teamId", entityColumn = "id", entity = TeamEntity::class)
    val team: TeamWithMembers?,
)

data class SeatWithPlayer(
    @Embedded val seat: SeatEntity,
    @Relation(parentColumn = "playerId", entityColumn = "id")
    val player: PlayerEntity?,
)

data class GameWithDetails(
    @Embedded val game: GameEntity,
    @Relation(parentColumn = "id", entityColumn = "gameId", entity = ParticipantEntity::class)
    val participants: List<ParticipantWithDetails>,
    @Relation(parentColumn = "id", entityColumn = "gameId", entity = SeatEntity::class)
    val seats: List<SeatWithPlayer>,
    @Relation(parentColumn = "id", entityColumn = "gameId")
    val edits: List<GameEditEntity> = emptyList(),
)
