package com.christianmolinari.scorecard

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.christianmolinari.scorecard.data.db.MIGRATION_1_2
import com.christianmolinari.scorecard.data.db.MIGRATION_2_3
import com.christianmolinari.scorecard.data.db.ScoreCardDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test.db"

/**
 * Exercises the hand-written Room migrations against a real SQLite.
 *
 * A migration that drifts from the entity definitions fails only when an
 * *existing* install is upgraded — never on a fresh install and never in the
 * JVM suite — so nothing but this test actually runs the SQL in
 * `ScoreCardDatabase`. The committed schema JSON is the expectation:
 * `runMigrationsAndValidate` compares the migrated database against it and
 * fails on any difference, which is how a stray `DEFAULT` clause or a missing
 * index gets caught before it reaches a device.
 *
 * These are instrumented tests — run them with `./gradlew :app:connectedDebugAndroidTest`
 * against a booted emulator or an attached device.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ScoreCardDatabase::class.java,
    )

    // v1 -> v2 adds game_edits and touches nothing else, so a game recorded
    // before the editing feature has to come through unchanged and simply read
    // as never edited.
    @Test
    fun migratesV1ToV2AddingAnEmptyGameEditsLog() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.seedClosedGame()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        assertEquals("game_edits should exist and be empty", 0L, db.longOf("SELECT COUNT(*) FROM game_edits"))
        db.assertSeededGameIntact()
        // The index the entity declares — missing indices are a classic
        // hand-written-migration omission, silent until a query gets slow.
        assertTrue(
            "index_game_edits_gameId should exist",
            db.longOf(
                "SELECT COUNT(*) FROM sqlite_master " +
                    "WHERE type = 'index' AND name = 'index_game_edits_gameId'",
            ) == 1L,
        )
    }

    // v2 -> v3 adds games.playedDateOnly. The column must be NULL for every
    // pre-existing row: null means "this row predates the column, fall back to
    // inferring from the stamp", which is not the same as false. A DEFAULT
    // clause here would both diverge from the entity (failing Room's own
    // validation) and rewrite history as "the time of day is meaningful".
    @Test
    fun migratesV2ToV3LeavingExistingGamesWithNoPlayedOnFlag() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.seedClosedGame()
            db.execSQL(
                "INSERT INTO game_edits (id, gameId, reason, editedAt) " +
                    "VALUES (1, 1, 'Miscounted the last hand', 4000)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        db.query("SELECT playedDateOnly FROM games WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("a pre-existing game's playedDateOnly must be NULL, not 0", cursor.isNull(0))
        }
        assertEquals("the edit log should survive the migration", 1L, db.longOf("SELECT COUNT(*) FROM game_edits"))
        db.assertSeededGameIntact()
        assertNull("playedDateOnly must carry no DEFAULT clause", db.defaultValueOf("games", "playedDateOnly"))
    }

    // The upgrade path an install that predates both features actually takes.
    @Test
    fun migratesV1ToV3InOneRun() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.seedClosedGame()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_1_2, MIGRATION_2_3)

        db.assertSeededGameIntact()
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM game_edits"))
        assertEquals(1L, db.longOf("SELECT COUNT(*) FROM games WHERE playedDateOnly IS NULL"))
    }

    // runMigrationsAndValidate compares schemas; this opens the upgraded file
    // through Room itself, which is what the app does on the next launch. It is
    // the check that covers the identity hash and proves the migrated rows are
    // still readable through the DAOs rather than merely present.
    @Test
    fun roomOpensAndReadsAMigratedDatabase() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.seedClosedGame()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, ScoreCardDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

        try {
            // Room migrates lazily, on the first actual database access.
            val games = runBlocking { database.gameDao().getAllWithDetails() }

            assertEquals(1, games.size)
            val game = games.single()
            assertEquals("Scopa", game.game.title)
            assertEquals(4, game.game.currentHand)
            assertNotNull("the game should still read as closed", game.game.closedAt)
            assertNull("a migrated row predates the flag", game.game.playedDateOnly)
            assertTrue("a game recorded before the editing feature has no edits", game.edits.isEmpty())

            val totals = game.participants
                .sortedBy { it.participant.sortIndex }
                .associate { it.participant.nameSnapshot to it.entries.sumOf { entry -> entry.points } }
            assertEquals(mapOf("Alice" to 21, "Bob" to 7), totals)
            assertEquals(2, game.seats.size)
        } finally {
            database.close()
        }
    }
}

/**
 * A closed two-player game, written with raw SQL against the columns that
 * existed at v1 — the entity classes have moved on, so they cannot be used to
 * populate an old schema.
 */
private fun SupportSQLiteDatabase.seedClosedGame() {
    execSQL("INSERT INTO players (id, name, createdAt) VALUES (1, 'Alice', 1000)")
    execSQL("INSERT INTO players (id, name, createdAt) VALUES (2, 'Bob', 1001)")
    execSQL("INSERT INTO game_names (id, name, createdAt, lastUsedAt) VALUES (1, 'Scopa', 1000, 2000)")
    execSQL(
        "INSERT INTO games (id, title, hasTarget, targetPoints, createdAt, closedAt, " +
            "latitude, longitude, locationName, currentDealerIndex, currentHand) " +
            "VALUES (1, 'Scopa', 1, 21, 2000, 3000, NULL, NULL, NULL, 1, 4)",
    )
    execSQL(
        "INSERT INTO participants (id, gameId, playerId, teamId, nameSnapshot, sortIndex) " +
            "VALUES (1, 1, 1, NULL, 'Alice', 0)",
    )
    execSQL(
        "INSERT INTO participants (id, gameId, playerId, teamId, nameSnapshot, sortIndex) " +
            "VALUES (2, 1, 2, NULL, 'Bob', 1)",
    )
    execSQL("INSERT INTO score_entries (id, participantId, points, timestamp) VALUES (1, 1, 11, 2100)")
    execSQL("INSERT INTO score_entries (id, participantId, points, timestamp) VALUES (2, 1, 10, 2200)")
    execSQL("INSERT INTO score_entries (id, participantId, points, timestamp) VALUES (3, 2, 7, 2300)")
    execSQL("INSERT INTO seats (id, gameId, playerId, position) VALUES (1, 1, 1, 0)")
    execSQL("INSERT INTO seats (id, gameId, playerId, position) VALUES (2, 1, 2, 1)")
}

/** Every migration is additive, so the seeded game must read back untouched. */
private fun SupportSQLiteDatabase.assertSeededGameIntact() {
    assertEquals(1L, longOf("SELECT COUNT(*) FROM games WHERE title = 'Scopa' AND currentHand = 4 AND closedAt = 3000"))
    assertEquals(2L, longOf("SELECT COUNT(*) FROM participants WHERE gameId = 1"))
    assertEquals(2L, longOf("SELECT COUNT(*) FROM seats WHERE gameId = 1"))
    assertEquals(21L, longOf("SELECT SUM(points) FROM score_entries WHERE participantId = 1"))
    assertEquals(7L, longOf("SELECT SUM(points) FROM score_entries WHERE participantId = 2"))
}

private fun SupportSQLiteDatabase.longOf(sql: String): Long =
    query(sql).use { cursor ->
        assertTrue("query returned no row: $sql", cursor.moveToFirst())
        cursor.getLong(0)
    }

/** The `dflt_value` PRAGMA column — null when the column declares no default. */
private fun SupportSQLiteDatabase.defaultValueOf(table: String, column: String): String? =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) {
                return if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex)
            }
        }
        throw AssertionError("no column $column on $table")
    }
