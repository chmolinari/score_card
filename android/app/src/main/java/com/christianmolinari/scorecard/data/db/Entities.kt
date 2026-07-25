package com.christianmolinari.scorecard.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.time.Instant

// Sentinel for a never-used game name's lastUsedAt. This is exactly what iOS
// writes for Swift's Date.distantPast through a JSONEncoder with .iso8601
// (verified: the encoder emits "0001-01-01T00:00:00Z", NOT the "0000-12-30"
// form that Date.description prints). Using the same value keeps never-used
// names tying with each other across a cross-platform backup round-trip.
val DISTANT_PAST: Instant = Instant.parse("0001-01-01T00:00:00Z")

// A single person who can play games, alone or as a member of one or more teams.
@Entity(tableName = "players")
data class PlayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Instant,
)

// A named group of players that competes as a single unit.
@Entity(tableName = "teams")
data class TeamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Instant,
)

// Many-to-many Player<->Team. Cascade both ways: deleting a player or team
// removes the membership row (mirrors SwiftData nullify of the relationship).
@Entity(
    tableName = "team_members",
    primaryKeys = ["teamId", "playerId"],
    foreignKeys = [
        ForeignKey(
            entity = TeamEntity::class,
            parentColumns = ["id"],
            childColumns = ["teamId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlayerEntity::class,
            parentColumns = ["id"],
            childColumns = ["playerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("teamId"), Index("playerId")],
)
data class TeamMemberCrossRef(val teamId: Long, val playerId: Long)

// A reusable game-name template (e.g. "Scopa", "Briscola") the user can pick
// from when starting a new game. Editable independently of the games that use
// it — deleting a name never touches past games (there is no relationship; a
// Game just copies the chosen name into its own title). lastUsedAt records
// when a game was last started with this name, so New Game can pre-select the
// most recently used one.
@Entity(tableName = "game_names")
data class GameNameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Instant,
    // DISTANT_PAST until first used, so freshly added names sort after ones
    // that have actually been used.
    val lastUsedAt: Instant,
)

// A single match. Holds its competitors (players and/or teams as participants),
// an optional target score, and the date/time/location it was created.
@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    // Some games race to a target (e.g. Scopa to 11/21); others are open-ended
    // and just track running totals (e.g. Briscola). targetPoints is only
    // meaningful when hasTarget is true.
    val hasTarget: Boolean = false,
    val targetPoints: Int? = null,
    // Every game is tagged with date + time. This is the creation (kick-off)
    // timestamp; closedAt is null while the game is in progress.
    val createdAt: Instant,
    val closedAt: Instant? = null,        // null => game still open
    // Every game is tagged with geolocation, stored as primitive components.
    // All optional because the user may decline location permission.
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,
    val currentDealerIndex: Int = 0,      // index into seats ordered by position
    val currentHand: Int = 1,             // 1-based hand (manche) counter
    // Whether createdAt records a date whose time of day is unknown — a game
    // registered without a played-on time. Inferring this from the stamp being
    // start-of-day cannot survive a change of time zone, and cannot tell a
    // deliberate 00:00 from "no time given", so the intent is stored. null
    // means the row predates this column: fall back to the old inference, which
    // is what it was written with. See docs/registering-past-games.md.
    val playedDateOnly: Boolean? = null,
)

// One competitor in one game: EITHER playerId OR teamId is set; nameSnapshot
// survives deletion of the underlying player/team (FKs use SET_NULL).
// gameId FK cascades. Index gameId, playerId, teamId.
@Entity(
    tableName = "participants",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlayerEntity::class,
            parentColumns = ["id"],
            childColumns = ["playerId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = TeamEntity::class,
            parentColumns = ["id"],
            childColumns = ["teamId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("gameId"), Index("playerId"), Index("teamId")],
)
data class ParticipantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: Long,
    val playerId: Long? = null,
    val teamId: Long? = null,
    // Snapshot of the competitor's name at the time the game was created. Used
    // as a fallback for history once the linked player/team is gone.
    val nameSnapshot: String,
    // Preserves the order participants were added in (for stable, non-score
    // tie-breaking in the scoreboard).
    val sortIndex: Int,
)

// A single scoring event for one participant (e.g. "+3"). Storing each addition
// individually gives an exact undo and a full per-game scoring log.
// participantId FK cascades; index participantId.
@Entity(
    tableName = "score_entries",
    foreignKeys = [
        ForeignKey(
            entity = ParticipantEntity::class,
            parentColumns = ["id"],
            childColumns = ["participantId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("participantId")],
)
data class ScoreEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val participantId: Long,
    val points: Int,
    val timestamp: Instant,
)

// One correction made to a closed game's scores. The user must type a reason
// before an edit can start, and that reason is kept here rather than discarded:
// a finished game's result is a record, so any change to it stays accountable.
// gameId FK cascades; index gameId.
@Entity(
    tableName = "game_edits",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("gameId")],
)
data class GameEditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: Long,
    val reason: String,
    val editedAt: Instant,
)

// One place at the table in a game, occupied by an individual player. Seats are
// ordered counter-clockwise starting from the first dealer (position 0), so the
// dealer for each successive hand is just the next seat around. Dealers are
// always individual people — even in a team game the seats hold the teams'
// members. playerId uses SET_NULL so a seat survives deleting the player.
// gameId FK cascades, playerId SET_NULL; indices on both.
@Entity(
    tableName = "seats",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlayerEntity::class,
            parentColumns = ["id"],
            childColumns = ["playerId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("gameId"), Index("playerId")],
)
data class SeatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: Long,
    val playerId: Long? = null,
    // 0-based position around the table. Position 0 is the first dealer; play
    // proceeds counter-clockwise through increasing positions.
    val position: Int,
)

// Store Instants as epoch millis: DISTANT_PAST is year 0, and toEpochMilli()
// handles negative (pre-1970) values fine, so it round-trips exactly.
object InstantConverters {
    @TypeConverter
    fun fromEpochMillis(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun toEpochMillis(value: Instant?): Long? = value?.toEpochMilli()
}
