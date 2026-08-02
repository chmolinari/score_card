package com.christianmolinari.scorecard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.christianmolinari.scorecard.data.db.GameEntity
import com.christianmolinari.scorecard.data.db.ParticipantEntity
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.ScoreCardDatabase
import com.christianmolinari.scorecard.data.db.ScoreEntryEntity
import com.christianmolinari.scorecard.domain.totalScore
import com.christianmolinari.scorecard.ui.games.GameDetailScreen
import com.christianmolinari.scorecard.ui.games.GameEditScreen
import com.christianmolinari.scorecard.ui.theme.ScoreCardTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant

private const val REASON_LABEL = "Why are these scores being changed?"
private const val REASON = "Miscounted the last hand"

/**
 * Drives the closed-game editor the way a user does.
 *
 * The two things pinned here are the ones a refactor could quietly remove
 * without failing anything else: the **mandatory reason** — a finished result
 * is a record, so there must be no path to the scores without one — and the
 * **Edited badge and history**, which is what makes a correction visible after
 * the fact. Both are covered by XCUITest on iOS; this is the Android side.
 * Behaviour is specified in `docs/game-editing.md`.
 *
 * The screens run against an in-memory database rather than the installed
 * app's, so a test run cannot touch real games on the device.
 */
class GameEditingUiTest {

    @get:Rule
    val compose = createComposeRule()

    private enum class Screen { EDITOR, DETAIL }

    private lateinit var database: ScoreCardDatabase
    private lateinit var container: AppContainer
    private var gameId: Long = 0

    // The composition is set up once per test — Compose does not allow a second
    // setContent — so the screen under test is swapped through this state
    // instead, which is also how the real navigation graph moves between them.
    private var screen by mutableStateOf(Screen.EDITOR)
    private var doneCount by mutableStateOf(0)
    private var launched = false

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ScoreCardDatabase::class.java).build()
        container = AppContainer(context, database)
        gameId = seedClosedGame()
    }

    // Deliberately no database.close(). A JUnit @Rule wraps @After, so the
    // compose rule disposes the composition *after* this method runs — closing
    // the database here races the screen's still-live Flow collectors and fails
    // intermittently with "The database ':memory:' is not open" in whichever
    // test happens to lose. The database is in-memory and a fresh one is built
    // per test, so letting it fall out of scope costs nothing.

    // There must be no route from the editor's first step to the scores while
    // the reason is empty — including one that looks filled but is only spaces.
    @Test
    fun continueStaysDisabledUntilARealReasonIsTyped() {
        showEditor()

        compose.onNodeWithText("Reason for Edit").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsNotEnabled()

        compose.onNodeWithText(REASON_LABEL).performTextInput("   ")
        compose.onNodeWithText("Continue").assertIsNotEnabled()

        compose.onNodeWithText(REASON_LABEL).performTextReplacement(REASON)
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    // The scores step is genuinely absent until Continue is taken, not merely
    // covered up: a blocked-but-present field could still be typed into.
    @Test
    fun theScoresStepIsUnreachableUntilTheReasonIsGiven() {
        showEditor()

        compose.onNodeWithText("Final Scores").assertDoesNotExist()
        compose.onNodeWithContentDescription("Raise Alice's total").assertDoesNotExist()

        // Actually attempt the bypass rather than only observing that the step
        // starts hidden: a click on the disabled Continue must not advance, and
        // neither must one on a reason that is only whitespace. Asserting the
        // button's enabled state alone would pass if the gate moved to a
        // greyed-out button that still worked when tapped.
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Final Scores").assertDoesNotExist()

        compose.onNodeWithText(REASON_LABEL).performTextInput("   ")
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Final Scores").assertDoesNotExist()

        compose.onNodeWithText(REASON_LABEL).performTextReplacement(REASON)
        compose.onNodeWithText("Continue").performClick()

        compose.onNodeWithText("Edit Scores").assertIsDisplayed()
        compose.onNodeWithText("Final Scores").assertIsDisplayed()
    }

    // Save arms on a total actually moving, not on having visited the step —
    // and disarms again when the total is put back, so a there-and-back edit
    // cannot log a correction that changed nothing.
    @Test
    fun saveArmsOnlyWhileATotalDiffersFromItsOriginal() {
        showScoresStep()

        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onNodeWithContentDescription("Raise Alice's total").performClick()
        compose.onNodeWithText("Save").assertIsEnabled()

        compose.onNodeWithContentDescription("Lower Alice's total").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    // End to end: the correction reaches the database as a delta entry plus an
    // edit row carrying the typed reason, and the game stays closed.
    @Test
    fun savingWritesTheCorrectionAndItsReason() {
        showScoresStep()

        compose.onNodeWithContentDescription("Raise Alice's total").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { doneCount == 1 }

        val game = runBlocking { database.gameDao().getAllWithDetails() }.single()
        assertEquals(REASON, game.edits.single().reason)
        assertEquals(22, game.participants.single { it.participant.nameSnapshot == "Alice" }.totalScore)
        assertEquals(7, game.participants.single { it.participant.nameSnapshot == "Bob" }.totalScore)
        assertNotNull("an edit must never reopen the game", game.game.closedAt)
    }

    // The badge and the history are what make a correction visible afterwards.
    // An unedited game must show neither — a badge that is always on says
    // nothing.
    @Test
    fun gameDetailShowsNoEditHistoryBeforeAnyCorrection() {
        showDetail()

        compose.onNodeWithText("Edited").assertDoesNotExist()
        compose.onNodeWithText("Edit History").assertDoesNotExist()
    }

    @Test
    fun gameDetailShowsTheEditedBadgeAndTheReasonAfterACorrection() {
        showScoresStep()
        compose.onNodeWithContentDescription("Raise Alice's total").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { doneCount == 1 }

        showDetail()

        compose.onNodeWithText("Edited").assertIsDisplayed()
        compose.onNodeWithText("Edit History").assertIsDisplayed()
        compose.onNodeWithText(REASON).assertIsDisplayed()
    }

    private fun launch() {
        if (launched) return
        launched = true
        compose.setContent {
            ScoreCardTheme {
                when (screen) {
                    Screen.EDITOR -> GameEditScreen(
                        container = container,
                        gameId = gameId,
                        onDone = { doneCount++ },
                    )

                    Screen.DETAIL -> GameDetailScreen(
                        container = container,
                        gameId = gameId,
                        onBack = {},
                        onEditScores = {},
                    )
                }
            }
        }
    }

    private fun showEditor() {
        screen = Screen.EDITOR
        launch()
        // Both screens show a spinner until the game flow emits its first value.
        awaitText("Reason for Edit")
    }

    private fun showScoresStep() {
        showEditor()
        compose.onNodeWithText(REASON_LABEL).performTextInput(REASON)
        compose.onNodeWithText("Continue").performClick()
        awaitText("Final Scores")
    }

    private fun showDetail() {
        screen = Screen.DETAIL
        launch()
        awaitText("Final Standings")
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** A closed two-player game: Alice on 21, Bob on 7. */
    private fun seedClosedGame(): Long = runBlocking {
        val dao = database.gameDao()
        val now = Instant.ofEpochSecond(1_700_000_000)
        val alice = database.playerDao().insert(PlayerEntity(name = "Alice", createdAt = now))
        val bob = database.playerDao().insert(PlayerEntity(name = "Bob", createdAt = now))
        val id = dao.insertGame(
            GameEntity(
                title = "Scopa",
                hasTarget = true,
                targetPoints = 21,
                createdAt = now,
                closedAt = now.plusSeconds(3600),
                currentHand = 4,
            ),
        )
        val aliceParticipant = dao.insertParticipant(
            ParticipantEntity(gameId = id, playerId = alice, nameSnapshot = "Alice", sortIndex = 0),
        )
        val bobParticipant = dao.insertParticipant(
            ParticipantEntity(gameId = id, playerId = bob, nameSnapshot = "Bob", sortIndex = 1),
        )
        dao.insertScoreEntry(ScoreEntryEntity(participantId = aliceParticipant, points = 21, timestamp = now))
        dao.insertScoreEntry(ScoreEntryEntity(participantId = bobParticipant, points = 7, timestamp = now))
        id
    }
}
