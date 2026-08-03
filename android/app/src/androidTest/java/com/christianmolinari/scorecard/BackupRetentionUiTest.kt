package com.christianmolinari.scorecard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.christianmolinari.scorecard.data.db.ScoreCardDatabase
import com.christianmolinari.scorecard.domain.BackupRetention
import com.christianmolinari.scorecard.ui.settings.SettingsScreen
import com.christianmolinari.scorecard.ui.theme.ScoreCardTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The retention control on the Settings screen: the number is adjustable and
 * lowering it never silently destroys anything.
 *
 * The arithmetic — the ten-by-default, the floor of one, and which files a
 * device may remove — is pinned by `BackupRetentionTest` on the JVM, where it
 * can be asserted exactly. What only a UI test can show is that the control is
 * wired to the preference at all, which is what this covers.
 *
 * Runs against an in-memory database, so a test run cannot touch real data.
 */
class BackupRetentionUiTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: ScoreCardDatabase
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ScoreCardDatabase::class.java).build()
        container = AppContainer(context, database)
    }

    // Deliberately no database.close(). A JUnit @Rule wraps @After, so the
    // compose rule disposes the composition *after* this method runs — closing
    // the database here races the screen's still-live Flow collectors.

    private fun showSettings() {
        compose.setContent {
            ScoreCardTheme {
                SettingsScreen(
                    container = container,
                    onOpenBackups = {},
                    onOpenHelp = {},
                    onOpenActionLog = {},
                )
            }
        }
        scrollToRetentionRow()
        compose.onNodeWithText("Keep backups").assertIsDisplayed()
    }

    // Settings is a LazyColumn, so a row below the fold is not composed at all
    // and performScrollTo cannot reach it — the list itself has to be scrolled
    // until the node exists.
    private fun scrollToRetentionRow() {
        // onFirst: the open selector is scrollable too, so once it has been
        // used there is more than one scrollable node in the tree. The settings
        // list is the outer one.
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Keep backups"))
    }

    // Opens the rolling selector and picks a value. One move, so the screen
    // asks about a reduction once rather than at every number on the way down —
    // which is what the +/- buttons this replaced used to do.
    private fun choose(value: String) {
        scrollToRetentionRow()
        compose.onNodeWithText("Keep backups").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText(value).onFirst().performClick()
        compose.waitForIdle()
    }

    @Test
    fun theSelectorPicksAValueAndKeepsIt() {
        showSettings()

        // A distinctive number, so the assertion cannot match anything else on
        // screen. Nothing is assumed about where it starts: the preference
        // lives in the app's real DataStore and survives between runs.
        choose("37")

        scrollToRetentionRow()
        // Existence, not display: the row is scrolled into the list but its
        // value can still sit at the very edge of the viewport.
        compose.onAllNodesWithText("37").onFirst().assertExists()
    }

    @Test
    fun loweringNeverDeletesWithoutAsking() {
        showSettings()
        choose("37")

        // With no backups on disk there is nothing to propose, so lowering is
        // silent. What must never happen is a deletion dialog appearing when
        // there is nothing to delete — or, worse, no dialog and a deletion.
        choose("2")

        compose.onNodeWithText("older backup", substring = true).assertDoesNotExist()
        scrollToRetentionRow()
        compose.onAllNodesWithText("2").onFirst().assertExists()
    }
}
