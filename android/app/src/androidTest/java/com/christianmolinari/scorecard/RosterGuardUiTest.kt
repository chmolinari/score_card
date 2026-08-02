package com.christianmolinari.scorecard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.ScoreCardDatabase
import com.christianmolinari.scorecard.data.db.TeamEntity
import com.christianmolinari.scorecard.data.db.TeamMemberCrossRef
import com.christianmolinari.scorecard.ui.players.PlayersScreen
import com.christianmolinari.scorecard.ui.theme.ScoreCardTheme
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The roster guard on the Players tab: deleting someone must ask first, and the
 * question must say which teams the deletion would break.
 *
 * This is the case that went wrong in the field. A team's name is free text, so
 * a roster that silently collapsed to one member still *looked* complete
 * everywhere, and the loss only surfaced a week later at the seating step. The
 * confirmation is what makes the knock-on effect visible at the moment it
 * happens, so the wording is pinned here, not just the dialog's existence.
 *
 * Following the house convention the invariant is asserted by **attempting the
 * bypass** — the swipe is performed and the player must still be in the
 * database afterwards — rather than by observing that a dialog appeared.
 *
 * The screen runs against an in-memory database, so a test run cannot touch
 * real players on the device.
 */
class RosterGuardUiTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: ScoreCardDatabase
    private lateinit var container: AppContainer
    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ScoreCardDatabase::class.java).build()
        container = AppContainer(context, database)
        seedTeamOfTwo()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // Alice and Bob make up the team "Reds", so deleting either one leaves it
    // below the two-member minimum.
    private fun seedTeamOfTwo() = runBlocking {
        val alice = database.playerDao().insert(PlayerEntity(name = "Alice", createdAt = t0))
        val bob = database.playerDao().insert(PlayerEntity(name = "Bob", createdAt = t0))
        val reds = database.teamDao().insert(TeamEntity(name = "Reds", createdAt = t0))
        database.teamDao().insertMembers(
            listOf(
                TeamMemberCrossRef(teamId = reds, playerId = alice),
                TeamMemberCrossRef(teamId = reds, playerId = bob),
            ),
        )
    }

    private fun showPlayers() {
        compose.setContent { ScoreCardTheme { PlayersScreen(container) } }
        compose.onNodeWithText("Alice").assertIsDisplayed()
    }

    private fun playerNames(): List<String> = runBlocking {
        database.playerDao().getAll().map { it.name }
    }

    @Test
    fun swipingAPlayerAsksBeforeDeletingAndNamesTheTeamItBreaks() {
        showPlayers()

        compose.onNodeWithText("Alice").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText("Delete Alice?").assertIsDisplayed()
        // The knock-on effect, spelled out — the part a free-text team name
        // hides. Matched on the warning sentence rather than the bare team
        // name, which also appears in the row caption behind the dialog.
        compose.onNodeWithText("Reds would be left with too few members", substring = true)
            .assertIsDisplayed()

        // Attempting the destructive gesture must not have written anything yet.
        assertEquals(listOf("Alice", "Bob"), playerNames().sorted())
    }

    @Test
    fun cancellingLeavesThePlayerInPlace() {
        showPlayers()

        compose.onNodeWithText("Alice").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertEquals(listOf("Alice", "Bob"), playerNames().sorted())
        compose.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun confirmingDeletesOnlyThatPlayer() {
        showPlayers()

        compose.onNodeWithText("Alice").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Delete Player").performClick()
        compose.waitForIdle()

        assertEquals(listOf("Bob"), playerNames())
        // The team survives the deletion — it is simply left under strength,
        // which is what the confirmation warned about.
        assertNotNull(runBlocking { database.teamDao().getAllWithMembers().firstOrNull() })
    }
}
