//
//  ActionLogUITests.swift
//  ScoreCardUITests
//
//  The one link the unit tests structurally cannot cover: that the recorder is
//  actually started by the shipping app, and that what it writes comes back out
//  through the Settings screens. Everything in between — the willSave hook, the
//  file format, the rolling — is covered off-device; this proves the wiring.
//
//  The app is launched with "-actionLogTesting", which redirects the log to a
//  throwaway directory. Without it a UI test would write its churn into the log
//  belonging to whoever owns the device.
//

import XCTest

final class ActionLogUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch() -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait   // launch tests may leave landscape
        let app = XCUIApplication()
        app.launchArguments += ["-uitesting", "-actionLogTesting"]
        app.launch()
        return app
    }

    @MainActor
    private func openLoggingSettings(_ app: XCUIApplication) {
        app.tabBars.buttons["Settings"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        // The Logging section sits below the fold on a phone. The row's label
        // carries the log's current size ("View Log, Zero KB"), so match on the
        // prefix rather than the bare title.
        let viewLog = viewLogRow(app)
        var swipes = 0
        while !viewLog.isHittable && swipes < 8 {
            app.swipeUp()
            swipes += 1
        }
        XCTAssertTrue(viewLog.isHittable, "The Logging section should be reachable in Settings")
    }

    /// Two inline-created players, a game name, then Start Game — the flow that
    /// ends in an explicit `modelContext.save()`.
    @MainActor
    private func startAGame(_ app: XCUIApplication, named name: String) {
        app.tabBars.buttons["Games"].tap()
        app.buttons["New Game"].firstMatch.tap()
        for player in ["Alice", "Bob"] {
            app.buttons["New Player"].firstMatch.tap()
            let field = app.textFields["Player name"]
            XCTAssertTrue(field.waitForExistence(timeout: 5))
            field.tap()
            field.typeText(player)
            app.buttons["Save"].firstMatch.tap()
        }
        app.buttons["New Game Name"].tap()
        let nameField = app.textFields["Game name (e.g. Scopa, Briscola)"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 5))
        nameField.tap()
        nameField.typeText(name)
        app.buttons["Save"].firstMatch.tap()

        let next = app.buttons["Next"]
        XCTAssertTrue(next.waitForExistence(timeout: 5))
        next.tap()
        let start = app.buttons["Start Game"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        start.tap()
    }

    /// Flips the "Record actions" toggle and confirms it actually moved.
    /// Tapping the composite row element does not flip a SwiftUI `Toggle`; the
    /// inner switch is the thing that responds.
    @MainActor
    private func setRecording(_ app: XCUIApplication, on: Bool) {
        let row = app.switches["Record actions"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        let wanted = on ? "1" : "0"
        if row.value as? String == wanted { return }
        let inner = row.switches.firstMatch
        (inner.exists ? inner : row).tap()
        XCTAssertEqual(row.value as? String, wanted,
                       "The Record actions toggle should now be \(on ? "on" : "off")")
    }

    /// Scrolls the log until `action` is on screen. The viewer is a lazy list,
    /// so an entry below the fold is not in the accessibility tree at all.
    @MainActor
    private func findInLog(_ app: XCUIApplication, _ action: String) -> Bool {
        let element = app.staticTexts[action]
        for _ in 0..<10 {
            if element.exists { return true }
            app.swipeUp()
        }
        return element.exists
    }

    @MainActor
    private func viewLogRow(_ app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "View Log")).firstMatch
    }

    /// Start a game in the real app, then read it back out of the log viewer.
    ///
    /// Starting a game is used rather than, say, adding a player because the
    /// recorder records what actually *persists*: New Game saves explicitly,
    /// whereas a bare insert waits on SwiftData's autosave and may not have
    /// landed while the test is still running.
    @MainActor
    func testAnActionTakenInTheAppShowsUpInTheLogViewer() throws {
        let app = launch()
        // Preferences survive a relaunch on the simulator, so the starting
        // state is established rather than assumed — otherwise this test
        // inherits whatever the previous run happened to leave behind.
        openLoggingSettings(app)
        setRecording(app, on: true)

        startAGame(app, named: "Friendly Match")

        openLoggingSettings(app)
        viewLogRow(app).tap()

        XCTAssertTrue(app.navigationBars["Action Log"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["No Actions Recorded"].exists,
                       "The running app should have recorded the game it just started")
        XCTAssertTrue(findInLog(app, "gameCreated"),
                      "Starting a game should have been recorded by the running app")
    }

    /// Turning recording off must stop new entries — the toggle has to reach the
    /// recorder, not just the preference.
    @MainActor
    func testTurningRecordingOffStopsNewEntries() throws {
        let app = launch()

        openLoggingSettings(app)
        // Same reason: establish, don't assume. That recording defaults to on
        // is pinned by the unit tests, not here.
        setRecording(app, on: true)
        setRecording(app, on: false)

        // Anything done now must leave no trace, including the explicit save
        // that starting a game performs.
        startAGame(app, named: "Silent Match")

        openLoggingSettings(app)
        viewLogRow(app).tap()
        XCTAssertTrue(app.navigationBars["Action Log"].waitForExistence(timeout: 10))
        // The log is not empty, and shouldn't be: switching recording off is
        // itself recorded, deliberately, so the trail explains why it stops
        // rather than just ending. What must be absent is everything after it.
        XCTAssertTrue(app.staticTexts["loggingDisabled"].waitForExistence(timeout: 5),
                      "Switching recording off should leave a note saying so")
        XCTAssertFalse(findInLog(app, "gameCreated"),
                       "Nothing should be recorded once recording is switched off")

        // Preferences outlive the app, so leave recording as it was found —
        // otherwise this test quietly disables logging for every suite that
        // runs after it on the same simulator.
        app.navigationBars["Action Log"].buttons.firstMatch.tap()
        setRecording(app, on: true)
    }
}
