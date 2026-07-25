// A portable representation of the whole database, used for manual
// backup/restore. Relationships are encoded as array indices (not database
// IDs) so the snapshot is self-contained and survives a full wipe.
//
// CROSS-PLATFORM CONTRACT: this JSON must round-trip with the iOS app
// (BackupSnapshot.swift). Field names are the default kotlinx names and match
// the Swift property names exactly; dates are ISO-8601 UTC at seconds
// precision; null optionals are omitted (like Swift's encodeIfPresent).

package com.christianmolinari.scorecard.data.backup

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

typealias IsoInstant = @Serializable(with = IsoInstantSerializer::class) Instant

// ISO-8601 UTC with seconds precision and NO fractional seconds: Swift's
// .iso8601 decoding strategy rejects fractional seconds, so truncate on write.
// On read, accept anything Instant.parse takes, falling back to
// OffsetDateTime for non-UTC offsets the iOS side could theoretically emit.
object IsoInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("IsoInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.truncatedTo(ChronoUnit.SECONDS).toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            OffsetDateTime.parse(text).toInstant()
        }
    }
}

@Serializable
data class BackupSnapshot(
    // Bumped if the format ever changes, so restore can refuse the unknown.
    val version: Int = CURRENT_VERSION,
    val exportedAt: IsoInstant,
    val players: List<PlayerDTO> = emptyList(),
    val teams: List<TeamDTO> = emptyList(),
    val games: List<GameDTO> = emptyList(),
    // Optional so backups written before the editable game-name list still
    // decode (and so older app versions, which ignore unknown keys, can still
    // restore newer backups — hence the format version is left unchanged).
    val gameNames: List<GameNameDTO>? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class PlayerDTO(
    val name: String,
    val createdAt: IsoInstant,
)

@Serializable
data class GameNameDTO(
    val name: String,
    val createdAt: IsoInstant,
    val lastUsedAt: IsoInstant,
)

@Serializable
data class TeamDTO(
    val name: String,
    val createdAt: IsoInstant,
    // Indices into `players`.
    val memberIndices: List<Int>,
)

@Serializable
data class GameDTO(
    val title: String,
    val hasTarget: Boolean,
    val targetPoints: Int? = null,
    val createdAt: IsoInstant,
    val closedAt: IsoInstant? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,
    val participants: List<ParticipantDTO> = emptyList(),
    // Optional so backups written before seating was added still decode.
    val seats: List<SeatDTO>? = null,
    val currentDealerIndex: Int? = null,
    // Optional so backups written before the hand counter still decode.
    val currentHand: Int? = null,
    // Corrections made to this game's scores after it closed, newest first.
    // Optional so backups written before editing was added still decode (and so
    // app versions predating it can still restore newer backups — hence the
    // format version is left unchanged).
    val edits: List<GameEditDTO>? = null,
    // Whether createdAt records a date whose time of day is unknown. Optional
    // in both directions: absent means "recorded before the field existed",
    // which readers fall back to inferring, so the format version is
    // deliberately unchanged.
    val playedDateOnly: Boolean? = null,
)

@Serializable
data class GameEditDTO(
    val reason: String,
    val editedAt: IsoInstant,
)

@Serializable
data class SeatDTO(
    val position: Int,
    // Index into `players`, or null if the seated player was deleted.
    val playerIndex: Int? = null,
)

@Serializable
data class ParticipantDTO(
    val nameSnapshot: String,
    val sortIndex: Int,
    // Index into `players` if this competitor is a single player.
    val playerIndex: Int? = null,
    // Index into `teams` if this competitor is a team.
    val teamIndex: Int? = null,
    val entries: List<EntryDTO> = emptyList(),
)

@Serializable
data class EntryDTO(
    val points: Int,
    val timestamp: IsoInstant,
)

class BackupException(message: String) : Exception(message)

object BackupCodec {
    val json: Json = Json {
        // Skip keys a newer format might add, like Swift's synthesized Codable.
        ignoreUnknownKeys = true
        // Omit null optionals, like Swift's encodeIfPresent.
        explicitNulls = false
        // REQUIRED so `version` (a defaulted property) is always written.
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(snapshot: BackupSnapshot): String = json.encodeToString(BackupSnapshot.serializer(), snapshot)

    // Decode and validate backup JSON into a snapshot. The messages match the
    // iOS BackupError copy so both apps tell the user the same thing.
    fun decode(text: String): BackupSnapshot {
        val snapshot = try {
            json.decodeFromString(BackupSnapshot.serializer(), text)
        } catch (_: Exception) {
            throw BackupException("That file isn't a valid ScoreCard backup.")
        }
        if (snapshot.version > BackupSnapshot.CURRENT_VERSION) {
            throw BackupException(
                "This backup was made by a newer version of ScoreCard (format ${snapshot.version}) and can't be restored."
            )
        }
        return snapshot
    }
}
