package com.christianmolinari.scorecard

import com.christianmolinari.scorecard.data.backup.BackupMapping
import com.christianmolinari.scorecard.data.backup.GameEditDTO
import com.christianmolinari.scorecard.data.db.GameEditEntity
import com.christianmolinari.scorecard.data.db.GameEntity
import com.christianmolinari.scorecard.data.db.GameWithDetails
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The edit-history half of the backup contract. BackupJsonTest covers the JSON
// shape; this covers the conversion either side of it, which is where an edit
// could silently attach to the wrong game or come back in the wrong order.
class BackupMappingTest {

    private val older = Instant.parse("2026-06-01T19:00:00Z")
    private val newer = Instant.parse("2026-06-02T09:00:00Z")

    @Test
    fun `a game's edits are exported newest first`() {
        // Stored oldest-first, so the ordering is the property under test.
        val game = gameWith(
            edit(id = 1, reason = "Swapped the primiera", at = older),
            edit(id = 2, reason = "Miscounted the last scopa", at = newer),
        )

        val dtos = BackupMapping.gameEdits(game)

        assertEquals(listOf("Miscounted the last scopa", "Swapped the primiera"), dtos.map { it.reason })
        assertEquals(listOf(newer, older), dtos.map { it.editedAt })
    }

    @Test
    fun `a game with no edits exports an empty list`() {
        assertTrue(BackupMapping.gameEdits(gameWith()).isEmpty())
    }

    @Test
    fun `restored edits carry their reason and timestamp onto the right game`() {
        val dtos = listOf(
            GameEditDTO(reason = "Miscounted the last scopa", editedAt = newer),
            GameEditDTO(reason = "Swapped the primiera", editedAt = older),
        )

        val rows = BackupMapping.editEntities(gameId = 77, dtos = dtos)

        assertEquals(2, rows.size)
        // Every row must land on the game being restored, not on any other.
        assertTrue(rows.all { it.gameId == 77L })
        assertEquals(listOf("Miscounted the last scopa", "Swapped the primiera"), rows.map { it.reason })
        assertEquals(listOf(newer, older), rows.map { it.editedAt })
    }

    @Test
    fun `a backup with no edits key restores no edit rows`() {
        // dto.edits is null for any backup written before editing existed.
        assertTrue(BackupMapping.editEntities(gameId = 77, dtos = null).isEmpty())
        assertTrue(BackupMapping.editEntities(gameId = 77, dtos = emptyList()).isEmpty())
    }

    // --- helpers ---

    private fun edit(id: Long, reason: String, at: Instant) =
        GameEditEntity(id = id, gameId = 42, reason = reason, editedAt = at)

    private fun gameWith(vararg edits: GameEditEntity) =
        GameWithDetails(
            game = GameEntity(
                id = 42,
                title = "Scopa",
                createdAt = Instant.parse("2026-06-01T17:00:00Z"),
                closedAt = Instant.parse("2026-06-01T18:00:00Z"),
            ),
            participants = emptyList(),
            seats = emptyList(),
            edits = edits.toList(),
        )
}
