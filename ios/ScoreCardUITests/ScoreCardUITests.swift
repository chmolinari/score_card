//
//  ScoreCardUITests.swift
//  ScoreCardUITests
//
//  Created by Christian Molinari on 30/05/2026.
//

import XCTest

final class ScoreCardUITests: XCTestCase {

    override func setUpWithError() throws {
        // Put setup code here. This method is called before the invocation of each test method in the class.

        // In UI tests it is usually best to stop immediately when a failure occurs.
        continueAfterFailure = false

        // In UI tests it’s important to set the initial state - such as interface orientation - required for your tests before they run. The setUp method is a good place to do this.
    }

    override func tearDownWithError() throws {
        // Put teardown code here. This method is called after the invocation of each test method in the class.
    }

    /// Verifies that players can be created inline while creating a game, are
    /// auto-selected, and that the game can then be started — exercising the
    /// "add new players/teams during game creation" requirement end to end.
    @MainActor
    func testCreatePlayersInlineDuringGameCreation() throws {
        XCUIDevice.shared.orientation = .portrait   // launch tests may leave landscape
        let app = XCUIApplication()
        app.launchArguments += ["-uitesting"]   // clean in-memory store
        app.launch()

        // Open the New Game sheet (empty-state button or the toolbar +).
        let newGame = app.buttons["New Game"].firstMatch
        XCTAssertTrue(newGame.waitForExistence(timeout: 10))
        newGame.tap()

        // Create two players without leaving the New Game screen.
        addPlayerInline(app, name: "Alice")
        addPlayerInline(app, name: "Bob")

        // Both freshly created players are auto-selected and listed in order.
        XCTAssertTrue(app.staticTexts["1. Alice"].waitForExistence(timeout: 5),
                      "Inline-created player should be auto-selected and listed")
        XCTAssertTrue(app.staticTexts["2. Bob"].waitForExistence(timeout: 5))

        // Add a game name to the editable list; it auto-selects on creation.
        app.buttons["New Game Name"].tap()
        let nameField = app.textFields["Game name (e.g. Scopa, Briscola)"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 5))
        nameField.tap()
        nameField.typeText("Friendly Match")
        app.buttons["Save"].tap()
        XCTAssertTrue(app.staticTexts["Friendly Match"].waitForExistence(timeout: 5),
                      "The newly added game name should appear in the list")

        // Proceed to the seating/dealer step.
        let next = app.buttons["Next"]
        XCTAssertTrue(next.waitForExistence(timeout: 5))
        XCTAssertTrue(next.isEnabled, "Next should be enabled with a name and 2 competitors")
        next.tap()

        // The seating step picks a random first dealer; start the game from here.
        let startGame = app.buttons["Start Game"]
        XCTAssertTrue(startGame.waitForExistence(timeout: 5), "Seating step should appear")
        startGame.tap()

        XCTAssertTrue(app.staticTexts["Friendly Match"].waitForExistence(timeout: 10),
                      "The newly started game should show in the list")
    }

    /// Verifies the destructive "Delete All Data" reset asks for confirmation
    /// and then clears the store.
    @MainActor
    func testDeleteAllDataResetsTheStore() throws {
        XCUIDevice.shared.orientation = .portrait   // launch tests may leave landscape
        let app = XCUIApplication()
        app.launchArguments += ["-uitesting"]   // clean in-memory store
        app.launch()

        // Add a player so there is something to delete.
        app.tabBars.buttons["Players"].tap()
        let addPlayer = app.buttons["Add Player"].firstMatch
        XCTAssertTrue(addPlayer.waitForExistence(timeout: 10))
        addPlayer.tap()
        let field = app.textFields["Player name"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("Temp Player")
        app.buttons["Save"].tap()

        // Go to Settings and reset. "Back Up Now" lives below the fold since
        // the Scoring section was added, and the lazy List doesn't expose
        // off-screen rows — wait on the title instead, and scroll to controls.
        app.tabBars.buttons["Settings"].tap()
        XCTAssertTrue(app.staticTexts["Settings"].firstMatch.waitForExistence(timeout: 5))
        tapWhenScrolledIntoView(app.buttons["Delete All Data"], in: app)

        // Confirmation is required before anything is deleted.
        let confirm = app.buttons["Delete Everything"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "A confirmation must be shown")
        confirm.tap()

        // Success alert confirms the wipe ran.
        XCTAssertTrue(app.staticTexts["Data Deleted"].waitForExistence(timeout: 5))
        app.buttons["OK"].tap()

        // The store is actually empty: the Players tab shows its empty state.
        app.tabBars.buttons["Players"].tap()
        XCTAssertTrue(app.staticTexts["No Players"].waitForExistence(timeout: 5),
                      "Players list should be empty after a full reset")
    }

    /// Full round trip: create data, back it up, delete everything, then restore
    /// the backup from the in-app list and confirm the data returns.
    @MainActor
    func testBackupDeleteRestoreRoundTrip() throws {
        XCUIDevice.shared.orientation = .portrait   // launch tests may leave landscape
        let app = XCUIApplication()
        app.launchArguments += ["-uitesting"]   // clean in-memory store
        app.launch()

        // Create a uniquely-named player so we can recognise it after restore.
        let marker = "Backup Round Trip Player"
        app.tabBars.buttons["Players"].tap()
        let addPlayer = app.buttons["Add Player"].firstMatch
        XCTAssertTrue(addPlayer.waitForExistence(timeout: 10))
        addPlayer.tap()
        let nameField = app.textFields["Player name"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 5))
        nameField.tap()
        nameField.typeText(marker)
        app.buttons["Save"].tap()
        XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 5))

        // Back up. "Back Up Now" is below the fold (see the reset test), so
        // scroll it into view before tapping.
        app.tabBars.buttons["Settings"].tap()
        XCTAssertTrue(app.staticTexts["Settings"].firstMatch.waitForExistence(timeout: 5))
        tapWhenScrolledIntoView(app.buttons["Back Up Now"], in: app)
        XCTAssertTrue(app.staticTexts["Backup Complete"].waitForExistence(timeout: 10))
        app.buttons["OK"].tap()

        // Delete everything.
        tapWhenScrolledIntoView(app.buttons["Delete All Data"], in: app)
        XCTAssertTrue(app.buttons["Delete Everything"].waitForExistence(timeout: 5))
        app.buttons["Delete Everything"].tap()
        XCTAssertTrue(app.staticTexts["Data Deleted"].waitForExistence(timeout: 5))
        app.buttons["OK"].tap()

        // Confirm it's gone.
        app.tabBars.buttons["Players"].tap()
        XCTAssertTrue(app.staticTexts["No Players"].waitForExistence(timeout: 5))

        // Restore from the in-app backup list (newest is first).
        app.tabBars.buttons["Settings"].tap()
        tapWhenScrolledIntoView(app.buttons["Restore from Backup…"], in: app)
        XCTAssertTrue(app.staticTexts["Available Backups"].waitForExistence(timeout: 10),
                      "The backup just created should be listed")
        let firstBackup = app.buttons["backupRow"].firstMatch
        XCTAssertTrue(firstBackup.waitForExistence(timeout: 5), "A backup row should be present")
        firstBackup.tap()
        XCTAssertTrue(app.buttons["Replace All Data"].waitForExistence(timeout: 5))
        app.buttons["Replace All Data"].tap()
        XCTAssertTrue(app.staticTexts["Restore Complete"].waitForExistence(timeout: 10))
        app.buttons["OK"].tap()

        // The player is back.
        app.tabBars.buttons["Players"].tap()
        XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 5),
                      "Restored data should include the original player")
    }

    /// Taps the inline "New Player" button, enters a name, and saves.
    @MainActor
    private func addPlayerInline(_ app: XCUIApplication, name: String) {
        let newPlayer = app.buttons["New Player"]
        XCTAssertTrue(newPlayer.waitForExistence(timeout: 5))
        newPlayer.tap()

        let field = app.textFields["Player name"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(name)

        app.buttons["Save"].tap()
    }

    /// Scrolls the current scroll view up until the element is hittable, then
    /// taps it. SwiftUI's lazy `List` doesn't instantiate off-screen cells, so a
    /// control below the fold (e.g. "Delete All Data" at the bottom of Settings)
    /// isn't in the accessibility tree until it's scrolled into view.
    @MainActor
    private func tapWhenScrolledIntoView(_ element: XCUIElement,
                                         in app: XCUIApplication,
                                         maxSwipes: Int = 8) {
        var swipes = 0
        while !element.isHittable && swipes < maxSwipes {
            app.swipeUp()
            swipes += 1
        }
        XCTAssertTrue(element.isHittable,
                      "\(element) never scrolled into a hittable position after \(maxSwipes) swipes")
        element.tap()
    }
}
