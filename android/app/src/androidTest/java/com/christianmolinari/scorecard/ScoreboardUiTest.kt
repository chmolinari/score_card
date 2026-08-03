package com.christianmolinari.scorecard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.christianmolinari.scorecard.data.db.GameEntity
import com.christianmolinari.scorecard.data.db.GameWithDetails
import com.christianmolinari.scorecard.data.db.ParticipantEntity
import com.christianmolinari.scorecard.data.db.PlayerEntity
import com.christianmolinari.scorecard.data.db.ScoreCardDatabase
import com.christianmolinari.scorecard.data.db.ScoreEntryEntity
import com.christianmolinari.scorecard.data.db.SeatEntity
import com.christianmolinari.scorecard.domain.DrawDealingRule
import com.christianmolinari.scorecard.domain.totalScore
import com.christianmolinari.scorecard.ui.games.ScoreboardScreen
import com.christianmolinari.scorecard.ui.theme.ScoreCardTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant

private const val TARGET = 11

/**
 * Drives the live scoreboard the way a user does.
 *
 * The two rules pinned here are the ones `android/CLAUDE.md` calls load-bearing,
 * and both are the kind a refactor can quietly invert without failing anything
 * else:
 *
 *  - **Hand-baseline gating** — "Next Hand" arms on a *net score change* against
 *    each competitor's total at the hand's start, not on entries having been
 *    added. Points added and then taken back leave the hand a draw.
 *  - **Target lock** — reaching the target prompts once, declining locks
 *    scoring until the over-target score is corrected down, and the prompt only
 *    ever fires on a genuine crossing, so re-entering the board never re-pops
 *    it.
 *
 * Behaviour is specified in `docs/dealing-rules.md` and mirrored on iOS by
 * `ScoringSheetUITests`.
 *
 * The screen runs against an in-memory database rather than the installed app's,
 * so a test run cannot touch real games on the device.
 */
class ScoreboardUiTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: ScoreCardDatabase
    private lateinit var container: AppContainer
    private var gameId: Long = 0
    private var aliceParticipant: Long = 0
    private var bobParticipant: Long = 0
    private var launched = false

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ScoreCardDatabase::class.java)
            // Lets the waitUntil helpers below read the store from whichever
            // thread the test framework runs their condition on.
            .allowMainThreadQueries()
            .build()
        container = AppContainer(context, database)
        // Preferences are the real per-app DataStore — the screen reads the
        // dealing rules from it — so pin the two that change what the board
        // does. Both are set to their shipping defaults, so this only
        // normalises a device where they had been changed by hand.
        runBlocking {
            container.prefs.setDrawDealingRule(DrawDealingRule.Ask)
            container.prefs.setAllowNegativeScores(false)
        }
        seedOpenGame()
    }

    // Deliberately no database.close() — see the note in GameEditingUiTest: a
    // JUnit @Rule wraps @After, so closing here would race the screen's still
    // live Flow collectors.

    // MARK: the per-hand baseline

    // The opening state of every hand: nothing has been scored, so the hand can
    // only be resolved as a draw.
    @Test
    fun nextHandIsDisabledUntilTheHandActuallyScores() {
        showBoard()

        compose.onNodeWithText("Next Hand").assertIsNotEnabled()
        compose.onNodeWithText("Hand Was a Draw").assertIsEnabled()

        compose.onNodeWithContentDescription(add(3, "Alice")).performClick()
        awaitNextHand(enabled = true)

        // Scoring closes the quick-add buttons and the draw route in the same
        // move: this hand is now decided.
        compose.onNodeWithText("Hand Was a Draw").assertIsNotEnabled()
        compose.onNodeWithContentDescription(add(3, "Alice")).assertIsNotEnabled()
    }

    // Assert the invariant by attempting the bypass rather than by reading the
    // button's enabled flag: a greyed-out control that still worked when tapped
    // would pass the weaker check.
    @Test
    fun clickingTheDisabledNextHandDoesNotStartANewHand() {
        showBoard()

        compose.onNodeWithText("Next Hand").performClick()
        compose.waitForIdle()

        val game = readGame()
        assertEquals("an unscored hand must not advance", 1, game.game.currentHand)
        assertEquals("nor pass the deal", 0, game.game.currentDealerIndex)
    }

    // The rule that a score *count* would get wrong. Alice scores 3 and takes it
    // straight back; the hand is where it started, so it is a draw again and
    // "Next Hand" must disarm.
    @Test
    fun takingTheHandsPointsBackDisablesNextHandAgain() {
        showBoard()

        compose.onNodeWithContentDescription(add(3, "Alice")).performClick()
        awaitNextHand(enabled = true)

        // The quick-add buttons are closed by now, so the sheet behind the
        // ellipsis is the only way back — which is why it stays live.
        openSheetFor("Alice")
        compose.onNodeWithContentDescription("Subtract 3 points").performClick()
        awaitNextHand(enabled = false)
        closeSheet()

        assertEquals(0, totalOf(aliceParticipant))
        compose.onNodeWithText("Next Hand").assertIsNotEnabled()
        compose.onNodeWithText("Hand Was a Draw").assertIsEnabled()
    }

    // Advancing re-snapshots the baseline, so the new hand opens closed again
    // and the deal has moved on.
    @Test
    fun advancingTheHandReArmsTheBaselineAndPassesTheDeal() {
        showBoard()

        compose.onNodeWithContentDescription(add(3, "Alice")).performClick()
        awaitNextHand(enabled = true)
        compose.onNodeWithText("Next Hand").performClick()
        awaitGame { it.game.currentHand == 2 }

        assertEquals("the deal passes to the next seat", 1, readGame().game.currentDealerIndex)
        compose.onNodeWithText("Next Hand").assertIsNotEnabled()
        compose.onNodeWithText("Hand Was a Draw").assertIsEnabled()
        // Scoring reopens for the new hand even though Alice's total still
        // differs from where the *game* started.
        compose.onNodeWithContentDescription(add(3, "Alice")).assertIsEnabled()
    }

    // A drawn hand starts the next one without scoring; who deals is the user's
    // preference, and the Ask rule set in setUp puts the choice on screen.
    @Test
    fun aDrawnHandAsksWhoDealsNextAndCanKeepTheDealer() {
        showBoard()

        compose.onNodeWithText("Hand Was a Draw").performClick()
        awaitText("Hand was a draw")
        compose.onNodeWithText("Pass to Bob").assertIsDisplayed()

        compose.onNodeWithText("Alice deals again").performClick()
        awaitGame { it.game.currentHand == 2 }

        assertEquals("a redeal keeps the dealer", 0, readGame().game.currentDealerIndex)
    }

    // MARK: the target lock

    @Test
    fun reachingTheTargetPromptsAndDecliningLocksScoring() {
        seedScore(aliceParticipant, 6)
        showBoard()

        compose.onNodeWithContentDescription(add(5, "Alice")).performClick()
        awaitText("End the game?")

        compose.onNodeWithText("Not Yet").performClick()
        awaitText("Target reached!")

        // Locked, and locked against an actual tap — not merely greyed out.
        compose.onNodeWithContentDescription(add(1, "Alice")).assertIsNotEnabled()
        compose.onNodeWithContentDescription(add(1, "Alice")).performClick()
        compose.waitForIdle()
        assertEquals("a locked board must not take points", TARGET, totalOf(aliceParticipant))
        compose.onNodeWithText("Next Hand").assertIsNotEnabled()
    }

    @Test
    fun correctingTheOverTargetScoreDownClearsTheLock() {
        seedScore(aliceParticipant, 6)
        showBoard()
        reachTargetAndDecline()

        openSheetFor("Alice")
        compose.onNodeWithContentDescription("Subtract 1 point").performClick()
        awaitTotal(aliceParticipant, TARGET - 1)
        closeSheet()

        compose.onNodeWithText("Target reached!").assertDoesNotExist()
        // Alice is still above the hand's baseline of 6, so the hand stays
        // decided — what the correction restores is the ability to move on.
        compose.onNodeWithText("Next Hand").assertIsEnabled()
    }

    // Declining is remembered only for the crossing it answered: correcting the
    // score down re-arms the prompt for the next one.
    @Test
    fun crossingTheTargetAgainAfterACorrectionPromptsAgain() {
        seedScore(aliceParticipant, 6)
        showBoard()
        reachTargetAndDecline()

        openSheetFor("Alice")
        compose.onNodeWithContentDescription("Subtract 1 point").performClick()
        awaitTotal(aliceParticipant, TARGET - 1)

        compose.onNodeWithContentDescription("Add 1 point").performClick()
        awaitText("End the game?")
    }

    // Opening a board that is already over the target is not a crossing. The
    // lock and the banner apply, but the dialog must not appear — otherwise it
    // would re-pop on every return to the board and after every process death.
    @Test
    fun aBoardOpenedAlreadyOverTheTargetLocksWithoutPrompting() {
        seedScore(aliceParticipant, TARGET + 1)
        showBoard()

        awaitText("Target reached!")
        compose.waitForIdle()

        compose.onNodeWithText("End the game?").assertDoesNotExist()
        compose.onNodeWithContentDescription(add(1, "Alice")).assertIsNotEnabled()
        compose.onNodeWithText("Next Hand").assertIsNotEnabled()
    }

    // MARK: driving the screen

    /** The row control's accessibility name, as the screen builds it. */
    private fun add(amount: Int, name: String): String =
        "Add $amount ${if (amount == 1) "point" else "points"} to $name"

    private fun showBoard() {
        if (!launched) {
            launched = true
            compose.setContent {
                ScoreCardTheme {
                    ScoreboardScreen(container = container, gameId = gameId, onBack = {})
                }
            }
        }
        // The board is empty until the game flow emits its first value.
        awaitText("Current Hand")
    }

    private fun openSheetFor(name: String) {
        compose.onNodeWithContentDescription("More scoring options for $name").performClick()
        awaitText("Quick Subtract")
    }

    /**
     * Dismisses the bottom sheet through its scrim, as a user would.
     *
     * The scrim's action is invoked directly rather than tapped: the scrim
     * fills the screen, so a click at the centre of its bounds lands on the
     * sheet sitting on top of it and dismisses nothing.
     */
    private fun closeSheet() {
        compose.onNodeWithContentDescription("Close sheet")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Quick Subtract").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun reachTargetAndDecline() {
        compose.onNodeWithContentDescription(add(5, "Alice")).performClick()
        awaitText("End the game?")
        compose.onNodeWithText("Not Yet").performClick()
        awaitText("Target reached!")
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Waits for "Next Hand" to reach the given enabled state. */
    private fun awaitNextHand(enabled: Boolean) {
        compose.waitUntil(timeoutMillis = 5_000) {
            val node = compose.onAllNodesWithText("Next Hand").fetchSemanticsNodes().firstOrNull()
                ?: return@waitUntil false
            node.config.contains(SemanticsProperties.Disabled) != enabled
        }
        if (enabled) {
            compose.onNodeWithText("Next Hand").assertIsEnabled()
        } else {
            compose.onNodeWithText("Next Hand").assertIsNotEnabled()
        }
    }

    private fun awaitTotal(participantId: Long, expected: Int) {
        compose.waitUntil(timeoutMillis = 5_000) { totalOf(participantId) == expected }
    }

    private fun awaitGame(condition: (GameWithDetails) -> Boolean) {
        compose.waitUntil(timeoutMillis = 5_000) { condition(readGame()) }
    }

    private fun readGame(): GameWithDetails =
        runBlocking { database.gameDao().getAllWithDetails() }.single()

    private fun totalOf(participantId: Long): Int =
        readGame().participants.single { it.participant.id == participantId }.totalScore

    // MARK: fixtures

    /**
     * An open two-player game to 11, no scores yet: Alice seated first (so she
     * deals the opening hand and takes the top row), Bob next.
     */
    private fun seedOpenGame() = runBlocking {
        val dao = database.gameDao()
        val now = Instant.ofEpochSecond(1_700_000_000)
        val alice = database.playerDao().insert(PlayerEntity(name = "Alice", createdAt = now))
        val bob = database.playerDao().insert(PlayerEntity(name = "Bob", createdAt = now))
        gameId = dao.insertGame(
            GameEntity(
                title = "Scopa",
                hasTarget = true,
                targetPoints = TARGET,
                createdAt = now,
                closedAt = null,
                currentHand = 1,
                currentDealerIndex = 0,
            ),
        )
        aliceParticipant = dao.insertParticipant(
            ParticipantEntity(gameId = gameId, playerId = alice, nameSnapshot = "Alice", sortIndex = 0),
        )
        bobParticipant = dao.insertParticipant(
            ParticipantEntity(gameId = gameId, playerId = bob, nameSnapshot = "Bob", sortIndex = 1),
        )
        dao.insertSeat(SeatEntity(gameId = gameId, playerId = alice, position = 0))
        dao.insertSeat(SeatEntity(gameId = gameId, playerId = bob, position = 1))
    }

    /** Points already on the board when it opens, as a carried-over score. */
    private fun seedScore(participantId: Long, points: Int) = runBlocking {
        database.gameDao().insertScoreEntry(
            ScoreEntryEntity(
                participantId = participantId,
                points = points,
                timestamp = Instant.ofEpochSecond(1_700_000_000),
            )
        )
    }
}
