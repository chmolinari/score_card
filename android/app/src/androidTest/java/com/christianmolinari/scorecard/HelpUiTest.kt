package com.christianmolinari.scorecard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.christianmolinari.scorecard.data.db.ScoreCardDatabase
import com.christianmolinari.scorecard.domain.helpTopics
import com.christianmolinari.scorecard.ui.settings.HelpScreen
import com.christianmolinari.scorecard.ui.settings.HelpTopicScreen
import com.christianmolinari.scorecard.ui.settings.SettingsScreen
import com.christianmolinari.scorecard.ui.theme.ScoreCardTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Pins the route into the help page: Settings offers it, the index lists the
 * topics, and a topic opens its own page.
 *
 * What is deliberately *not* asserted here is the prose — that lives in
 * `docs/help-content.md` and is shape-checked by `HelpContentTest` on the Java
 * Virtual Machine. Rewording a topic must not break a UI test; losing the way in
 * from Settings must.
 *
 * Settings runs against an in-memory database rather than the installed app's,
 * so a test run cannot touch real games on the device.
 */
class HelpUiTest {

    @get:Rule
    val compose = createComposeRule()

    private enum class Screen { SETTINGS, HELP, TOPIC }

    private lateinit var database: ScoreCardDatabase
    private lateinit var container: AppContainer

    // The composition is set up once per test — Compose does not allow a second
    // setContent — so the screen under test is swapped through this state
    // instead, which is also how the real navigation graph moves between them.
    private var screen by mutableStateOf(Screen.SETTINGS)
    private var topicId by mutableStateOf("")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ScoreCardDatabase::class.java).build()
        container = AppContainer(context, database)
    }

    // Deliberately no database.close(). A JUnit @Rule wraps @After, so the
    // compose rule disposes the composition *after* this method runs — closing
    // the database here races the screen's still-live Flow collectors and fails
    // intermittently with "The database ':memory:' is not open" in whichever
    // test happens to lose. The database is in-memory and a fresh one is built
    // per test, so letting it fall out of scope costs nothing.

    @Test
    fun settingsOpensTheHelpIndexAndATopicOpensItsOwnPage() {
        val first = helpTopics.first()
        val second = helpTopics[1]

        launch()
        awaitText("Settings")

        compose.onNodeWithText("How to Use ScoreCard").performClick()
        awaitText("Help")
        // The index is the whole contract list, not just the row that was tapped.
        compose.onNodeWithText(first.title).assertIsDisplayed()
        compose.onNodeWithText(second.title).assertIsDisplayed()

        compose.onNodeWithText(first.title).performClick()
        awaitText(first.title)
        compose.onNodeWithText(first.title).assertIsDisplayed()
        // The topic page replaced the index rather than sitting on top of it.
        compose.onNodeWithText(second.title).assertDoesNotExist()
    }

    private fun launch() {
        compose.setContent {
            ScoreCardTheme {
                when (screen) {
                    Screen.SETTINGS -> SettingsScreen(
                        container = container,
                        onOpenBackups = {},
                        onOpenHelp = { screen = Screen.HELP },
                        onOpenActionLog = {},
                    )

                    Screen.HELP -> HelpScreen(
                        onOpenTopic = {
                            topicId = it
                            screen = Screen.TOPIC
                        },
                        onBack = { screen = Screen.SETTINGS },
                    )

                    Screen.TOPIC -> HelpTopicScreen(
                        topicId = topicId,
                        onBack = { screen = Screen.HELP },
                    )
                }
            }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
