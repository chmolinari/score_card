package com.christianmolinari.scorecard

import com.christianmolinari.scorecard.data.backup.BackupCodec
import com.christianmolinari.scorecard.data.backup.BackupException
import com.christianmolinari.scorecard.data.backup.BackupSnapshot
import com.christianmolinari.scorecard.data.backup.EntryDTO
import com.christianmolinari.scorecard.data.backup.GameDTO
import com.christianmolinari.scorecard.data.backup.GameEditDTO
import com.christianmolinari.scorecard.data.backup.GameNameDTO
import com.christianmolinari.scorecard.data.backup.ParticipantDTO
import com.christianmolinari.scorecard.data.backup.PlayerDTO
import com.christianmolinari.scorecard.data.backup.SeatDTO
import com.christianmolinari.scorecard.data.backup.TeamDTO
import com.christianmolinari.scorecard.data.db.DISTANT_PAST
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Backup JSON is the cross-platform contract: the same files must round-trip
// with the iOS app, so dates are second-precision ISO-8601 (Swift's .iso8601
// decoder rejects fractional seconds) and nil/null optionals are omitted
// (Swift's synthesized Codable uses encodeIfPresent).
class BackupJsonTest {

    private fun fullSnapshot() = BackupSnapshot(
        exportedAt = Instant.parse("2026-06-01T18:30:00Z"),
        players = listOf(
            PlayerDTO(name = "Alice", createdAt = Instant.parse("2026-06-01T10:00:00Z")),
            PlayerDTO(name = "Bob", createdAt = Instant.parse("2026-06-01T10:01:00Z")),
        ),
        teams = listOf(
            TeamDTO(
                name = "Aces",
                createdAt = Instant.parse("2026-06-01T10:02:00Z"),
                memberIndices = listOf(0, 1),
            ),
        ),
        games = listOf(
            GameDTO(
                title = "Scopa",
                hasTarget = true,
                targetPoints = 11,
                createdAt = Instant.parse("2026-06-01T17:00:00Z"),
                closedAt = Instant.parse("2026-06-01T18:00:00Z"),
                latitude = 40.8518,
                longitude = 14.2681,
                locationName = "Napoli, Italy",
                participants = listOf(
                    ParticipantDTO(
                        nameSnapshot = "Aces",
                        sortIndex = 0,
                        teamIndex = 0,
                        entries = listOf(
                            EntryDTO(points = 11, timestamp = Instant.parse("2026-06-01T17:30:00Z")),
                        ),
                    ),
                    ParticipantDTO(
                        nameSnapshot = "Alice",
                        sortIndex = 1,
                        playerIndex = 0,
                        entries = listOf(
                            EntryDTO(points = 4, timestamp = Instant.parse("2026-06-01T17:31:00Z")),
                            EntryDTO(points = 3, timestamp = Instant.parse("2026-06-01T17:32:00Z")),
                        ),
                    ),
                ),
                seats = listOf(
                    SeatDTO(position = 0, playerIndex = 0),
                    SeatDTO(position = 1, playerIndex = 1),
                ),
                currentDealerIndex = 1,
                currentHand = 3,
                playedDateOnly = true,
                // Listed newest-first here only because that is what a real
                // export produces; this test pins the JSON shape, not the
                // ordering (BackupMappingTest pins that).
                edits = listOf(
                    GameEditDTO(
                        reason = "Miscounted the last scopa",
                        editedAt = Instant.parse("2026-06-02T09:00:00Z"),
                    ),
                    GameEditDTO(
                        reason = "Swapped the primiera",
                        editedAt = Instant.parse("2026-06-01T19:00:00Z"),
                    ),
                ),
            ),
        ),
        gameNames = listOf(
            GameNameDTO(
                name = "Scopa",
                createdAt = Instant.parse("2026-06-01T09:00:00Z"),
                lastUsedAt = Instant.parse("2026-06-01T17:00:00Z"),
            ),
            // Never used: DISTANT_PAST must survive the trip so cross-platform
            // "never used" sorting stays identical after a restore.
            GameNameDTO(
                name = "Briscola",
                createdAt = Instant.parse("2026-06-01T09:01:00Z"),
                lastUsedAt = DISTANT_PAST,
            ),
        ),
    )

    @Test
    fun roundTripPreservesEverything() {
        val snapshot = fullSnapshot()

        val decoded = BackupCodec.decode(BackupCodec.encode(snapshot))

        // All DTOs are data classes with second-precision instants, so a single
        // equality check covers players, teams, games, participants, entries,
        // seats, dealer state, and game names.
        assertEquals(snapshot, decoded)
    }

    // Editing arrived after this format shipped, and the field was added as an
    // optional rather than bumping the version — so a backup written by any
    // earlier build (or by an iOS build predating the feature) must still load.
    @Test
    fun gameDecodesFromABackupWrittenWithoutEdits() {
        val json = """
            {
              "version": 1,
              "exportedAt": "2026-06-01T18:30:00Z",
              "players": [{ "name": "Alice", "createdAt": "2026-06-01T10:00:00Z" }],
              "teams": [],
              "games": [
                {
                  "title": "Briscola",
                  "hasTarget": false,
                  "createdAt": "2026-06-01T17:00:00Z",
                  "closedAt": "2026-06-01T18:00:00Z",
                  "participants": [
                    { "nameSnapshot": "Alice", "sortIndex": 0, "playerIndex": 0, "entries": [] }
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = BackupCodec.decode(json)

        val game = decoded.games.single()
        assertNull(game.edits)
        assertNull(game.playedDateOnly)
        // The other back-compat optionals still behave the same way.
        assertNull(game.seats)
        assertNull(game.currentHand)
    }

    @Test
    fun datesSerializeAtSecondPrecisionAndParseBack() {
        // Fractional seconds must be truncated on write: Swift's .iso8601
        // strategy refuses to decode them.
        val snapshot = BackupSnapshot(exportedAt = Instant.parse("2026-06-12T18:30:00.789Z"))

        val json = BackupCodec.encode(snapshot)

        assertTrue(json.contains("\"2026-06-12T18:30:00Z\""))
        assertFalse(json.contains("18:30:00.789"))
        assertEquals(Instant.parse("2026-06-12T18:30:00Z"), BackupCodec.decode(json).exportedAt)
    }

    @Test
    fun nullOptionalsAreOmittedButVersionIsPresent() {
        // An open-ended, unlocated, open game: every optional is null.
        val snapshot = BackupSnapshot(
            exportedAt = Instant.parse("2026-06-12T18:30:00Z"),
            games = listOf(
                GameDTO(
                    title = "Briscola",
                    hasTarget = false,
                    createdAt = Instant.parse("2026-06-12T18:00:00Z"),
                ),
            ),
        )

        val json = BackupCodec.encode(snapshot)

        assertFalse(json.contains("targetPoints"))
        assertFalse(json.contains("closedAt"))
        assertFalse(json.contains("locationName"))
        assertFalse(json.contains("latitude"))
        assertFalse(json.contains("gameNames"))
        // version is a defaulted property but MUST still be written, or iOS
        // backups would be unversioned.
        assertTrue(json.contains("\"version\""))
        assertEquals(BackupSnapshot.CURRENT_VERSION, BackupCodec.decode(json).version)
    }

    @Test
    fun decodesAnIosStyleFixture() {
        // Hand-written the way the iOS encoder emits it: camelCase keys,
        // second-precision UTC dates, optional keys absent rather than null,
        // and no gameNames array (older backups predate that field).
        val fixture = """
            {
              "version" : 1,
              "exportedAt" : "2026-06-01T18:30:00Z",
              "players" : [
                { "name" : "Alice", "createdAt" : "2026-06-01T10:00:00Z" },
                { "name" : "Bob", "createdAt" : "2026-06-01T10:01:00Z" }
              ],
              "teams" : [
                { "name" : "Aces", "createdAt" : "2026-06-01T10:02:00Z", "memberIndices" : [ 0, 1 ] }
              ],
              "games" : [
                {
                  "title" : "Scopa",
                  "hasTarget" : true,
                  "targetPoints" : 11,
                  "createdAt" : "2026-06-01T17:00:00Z",
                  "closedAt" : "2026-06-01T18:00:00Z",
                  "latitude" : 40.8518,
                  "longitude" : 14.2681,
                  "locationName" : "Napoli, Italy",
                  "participants" : [
                    {
                      "nameSnapshot" : "Aces",
                      "sortIndex" : 0,
                      "teamIndex" : 0,
                      "entries" : [ { "points" : 11, "timestamp" : "2026-06-01T17:30:00Z" } ]
                    },
                    {
                      "nameSnapshot" : "Alice",
                      "sortIndex" : 1,
                      "playerIndex" : 0,
                      "entries" : [
                        { "points" : 4, "timestamp" : "2026-06-01T17:31:00Z" },
                        { "points" : 3, "timestamp" : "2026-06-01T17:32:00Z" }
                      ]
                    }
                  ],
                  "seats" : [
                    { "position" : 0, "playerIndex" : 0 },
                    { "position" : 1, "playerIndex" : 1 }
                  ],
                  "currentDealerIndex" : 1,
                  "currentHand" : 3
                },
                {
                  "title" : "Briscola",
                  "hasTarget" : false,
                  "createdAt" : "2026-06-02T18:30:00Z",
                  "participants" : [ ]
                }
              ]
            }
        """.trimIndent()

        val decoded = BackupCodec.decode(fixture)

        assertEquals(1, decoded.version)
        assertEquals(Instant.parse("2026-06-01T18:30:00Z"), decoded.exportedAt)
        assertEquals(listOf("Alice", "Bob"), decoded.players.map { it.name })
        assertEquals(listOf(0, 1), decoded.teams.single().memberIndices)
        assertNull(decoded.gameNames)   // absent key decodes as null

        val scopa = decoded.games[0]
        assertEquals(11, scopa.targetPoints)
        assertEquals(Instant.parse("2026-06-01T18:00:00Z"), scopa.closedAt)
        assertEquals("Napoli, Italy", scopa.locationName)
        assertEquals(listOf("Aces", "Alice"), scopa.participants.map { it.nameSnapshot })
        assertEquals(0, scopa.participants[0].teamIndex)
        assertNull(scopa.participants[0].playerIndex)
        assertEquals(listOf(4, 3), scopa.participants[1].entries.map { it.points })
        assertEquals(listOf(0, 1), scopa.seats?.map { it.position })
        assertEquals(1, scopa.currentDealerIndex)
        assertEquals(3, scopa.currentHand)

        // The second game omitted every optional key.
        val briscola = decoded.games[1]
        assertNull(briscola.targetPoints)
        assertNull(briscola.closedAt)
        assertNull(briscola.latitude)
        assertNull(briscola.longitude)
        assertNull(briscola.locationName)
        assertNull(briscola.seats)
        assertNull(briscola.currentDealerIndex)
        assertNull(briscola.currentHand)
    }

    @Test
    fun decodingRejectsANewerFormatVersion() {
        val fromTheFuture = """{ "version" : 2, "exportedAt" : "2026-06-01T18:30:00Z" }"""

        val error = assertThrows(BackupException::class.java) {
            BackupCodec.decode(fromTheFuture)
        }
        assertEquals(
            "This backup was made by a newer version of ScoreCard (format 2) and can't be restored.",
            error.message,
        )
    }

    @Test
    fun decodingRejectsNonBackupData() {
        assertThrows(BackupException::class.java) {
            BackupCodec.decode("not a backup")
        }
    }

    @Test
    fun aTeamWithOneMemberStillDecodes() {
        // The two-member rule is an editing rule, not a storage invariant. Older
        // backups and the iOS app can both carry a smaller team — a deleted
        // player leaves one behind — and decoding must keep accepting it rather
        // than failing the whole restore.
        val shrunk = """
            {
              "version" : 1,
              "exportedAt" : "2026-08-01T16:14:58Z",
              "players" : [ { "name" : "Bassano", "createdAt" : "2026-06-02T13:05:28Z" } ],
              "teams" : [ { "name" : "Bassano e Pierangela",
                            "createdAt" : "2026-06-02T13:38:00Z",
                            "memberIndices" : [ 0 ] } ],
              "games" : [ ]
            }
        """.trimIndent()

        val snapshot = BackupCodec.decode(shrunk)
        assertEquals(1, snapshot.teams.size)
        assertEquals(listOf(0), snapshot.teams[0].memberIndices)
    }
}
