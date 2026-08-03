//
//  RosterGuardUITests.swift
//  ScoreCardUITests
//
//  The roster guards, driven end to end:
//
//  * a team game reaches the seating step with *every* team member — the case
//    that went wrong in the field, where two two-member teams expanded to two
//    people because a deletion had silently emptied their rosters;
//  * the team editor will not save a team below the two-member minimum;
//  * deleting a player asks first, and cancelling really does nothing.
//
//  Following the house convention, an invariant is asserted by *attempting the
//  bypass* — tapping the disabled control and checking the write did not happen
//  — rather than by asserting the control looks disabled.
//

import XCTest

final class RosterGuardUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch() -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait   // launch tests may leave landscape
        let app = XCUIApplication()
        app.launchArguments += ["-uitesting"]   // clean in-memory store
        app.launch()
        return app
    }

    // MARK: - The original bug

    /// Two teams of two must seat four people. Before the fix a team whose
    /// roster had collapsed still looked complete (its name is free text), so
    /// this is the assertion that would have caught it.
    @MainActor
    func testTeamGameSeatingListsEveryTeamMember() throws {
        let app = launch()

        for name in ["Alice", "Bob", "Carol", "Dave"] { addPlayer(app, named: name) }
        addTeam(app, named: "Reds", members: ["Alice", "Bob"])
        addTeam(app, named: "Blues", members: ["Carol", "Dave"])

        app.tabBars.buttons["Games"].tap()
        openNewGame(app)
        addGameName(app, "Friendly Match")

        selectCompetitor(app, named: "Reds")
        selectCompetitor(app, named: "Blues")
        XCTAssertTrue(app.staticTexts["1. Reds"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["2. Blues"].waitForExistence(timeout: 5))

        let next = app.buttons["Next"]
        XCTAssertTrue(next.waitForExistence(timeout: 5))
        next.tap()

        XCTAssertTrue(app.buttons["Start Game"].waitForExistence(timeout: 5),
                      "Seating step should appear")
        // One member deals; the other three are in the rotation list. All four
        // must be on screen — the bug showed only one member per team.
        for member in ["Alice", "Bob", "Carol", "Dave"] {
            XCTAssertTrue(app.staticTexts[member].waitForExistence(timeout: 5),
                          "\(member) should be seated — teams expand to all their members")
        }
    }

    // MARK: - Two-member minimum

    @MainActor
    func testTeamEditorRefusesToSaveASingleMemberTeam() throws {
        let app = launch()
        for name in ["Alice", "Bob"] { addPlayer(app, named: name) }

        app.tabBars.buttons["Teams"].tap()
        app.buttons["Add Team"].firstMatch.tap()

        let field = app.textFields["Team name"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("Half A Team")

        // One member only: attempt the bypass by tapping Save anyway.
        app.buttons["Alice"].firstMatch.tap()
        app.buttons["Save"].firstMatch.tap()
        XCTAssertTrue(field.exists,
                      "The editor should still be open — one member is below the minimum")

        // A second member arms it.
        app.buttons["Bob"].firstMatch.tap()
        let save = app.buttons["Save"].firstMatch
        XCTAssertTrue(save.isEnabled,
                      "Save should arm once two members are ticked. Field value: "
                      + String(describing: field.value)
                      + ". On screen: "
                      + app.staticTexts.allElementsBoundByIndex.map(\.label).prefix(25).joined(separator: " | "))
        save.tap()
        waitForDisappearance(field, "The editor should close once the team has two members")
        XCTAssertTrue(app.staticTexts["Half A Team"].waitForExistence(timeout: 10))
    }

    // MARK: - Delete confirmation

    @MainActor
    func testDeletingAPlayerAsksFirstAndNamesTheTeamsItBreaks() throws {
        let app = launch()
        for name in ["Alice", "Bob"] { addPlayer(app, named: name) }
        addTeam(app, named: "Reds", members: ["Alice", "Bob"])

        app.tabBars.buttons["Players"].tap()
        XCTAssertTrue(app.staticTexts["Alice"].waitForExistence(timeout: 5))

        // Swipe to delete — this must only *propose* the deletion.
        beginDelete(app, forRowContaining: "Alice")
        XCTAssertTrue(app.staticTexts["Delete Alice?"].waitForExistence(timeout: 5),
                      "Deleting a player must ask for confirmation first")

        // The whole point of the guard: the message spells out the knock-on
        // effect, which a free-text team name would otherwise hide completely.
        let warning = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "Reds would be left with too few members")
        ).firstMatch
        XCTAssertTrue(warning.waitForExistence(timeout: 5),
                      "The confirmation should name the team it would break. On screen: "
                      + app.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " | "))

        dismissConfirmation(app)
        XCTAssertTrue(app.staticTexts["Alice"].waitForExistence(timeout: 5),
                      "Backing out must leave the player in place")

        // Confirming really does delete, and only the swiped row.
        beginDelete(app, forRowContaining: "Alice")
        let confirm = app.buttons["Delete Player"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        confirm.tap()
        waitForDisappearance(app.staticTexts["Alice"], "Confirming should remove the player")
        XCTAssertTrue(app.staticTexts["Bob"].exists, "Only the swiped row should go")
    }

    // MARK: - Helpers

    /// Waits for an element to go away. `waitForExistence` cannot express this:
    /// asserting `XCTAssertFalse(element.waitForExistence(timeout: 3))` passes
    /// only if the element vanishes inside a fixed window, which turns into a
    /// false failure whenever a full suite run makes the simulator slow.
    @MainActor
    private func waitForDisappearance(_ element: XCUIElement,
                                      timeout: TimeInterval = 15,
                                      _ message: String) {
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"),
                                             object: element)
        XCTAssertEqual(XCTWaiter().wait(for: [gone], timeout: timeout), .completed, message)
    }

    /// Rows compose their contents into one accessibility label — a team row
    /// reads "Reds, Alice & Bob", a player row "A, Alice, Reds, No games yet" —
    /// so they are matched on a substring rather than an exact name.
    @MainActor
    private func row(_ app: XCUIApplication, containing name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", name)).firstMatch
    }

    /// Swipes a row and taps its Delete action, leaving the confirmation up.
    @MainActor
    private func beginDelete(_ app: XCUIApplication, forRowContaining name: String) {
        row(app, containing: name).swipeLeft()
        let action = app.buttons["Delete"].firstMatch
        XCTAssertTrue(action.waitForExistence(timeout: 5), "Swipe should reveal Delete")
        action.tap()
    }

    /// Backs out of the confirmation. SwiftUI renders a `confirmationDialog` as
    /// a popover here, which drops the cancel-role button in favour of a
    /// tap-outside dismiss region, so both shapes are handled.
    @MainActor
    private func dismissConfirmation(_ app: XCUIApplication) {
        let cancel = app.buttons["Cancel"].firstMatch
        if cancel.exists {
            cancel.tap()
            return
        }
        let dismissRegion = app.otherElements["PopoverDismissRegion"]
        XCTAssertTrue(dismissRegion.waitForExistence(timeout: 5),
                      "Expected either a Cancel button or a popover dismiss region")
        dismissRegion.tap()
    }

    @MainActor
    private func selectCompetitor(_ app: XCUIApplication, named name: String) {
        let element = row(app, containing: name)
        XCTAssertTrue(element.waitForExistence(timeout: 5), "\(name) should be listed")
        element.tap()
    }

    @MainActor
    private func addPlayer(_ app: XCUIApplication, named name: String) {
        app.tabBars.buttons["Players"].tap()
        app.buttons["Add Player"].firstMatch.tap()
        let field = app.textFields["Player name"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(name)
        app.buttons["Save"].firstMatch.tap()
        // Assert the sheet actually closed before trusting the name below.
        // Without this a rejected save leaves the editor up and the name check
        // still passes by matching the text field's own contents — which is how
        // a player silently failed to be created and only surfaced much later,
        // as a team editor that would not accept its second member.
        waitForDisappearance(field, "The player editor should close after saving \(name)")
        XCTAssertTrue(app.staticTexts[name].waitForExistence(timeout: 10),
                      "\(name) should be listed on the Players tab")
    }

    @MainActor
    private func addTeam(_ app: XCUIApplication, named name: String, members: [String]) {
        app.tabBars.buttons["Teams"].tap()
        app.buttons["Add Team"].firstMatch.tap()
        let field = app.textFields["Team name"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(name)
        for member in members {
            let row = app.buttons[member].firstMatch
            XCTAssertTrue(row.waitForExistence(timeout: 5), "\(member) should be pickable as a member")
            row.tap()
        }
        app.buttons["Save"].firstMatch.tap()
        // Assert the editor actually closed. Without this a rejected save (too
        // few members selected) leaves the sheet up, and the name assertion
        // below would still pass by matching the text field's own contents —
        // hiding the real failure until much later in the test.
        waitForDisappearance(field,
                             "The team editor should close once \(name) has \(members.count) members")
        XCTAssertTrue(app.staticTexts[name].waitForExistence(timeout: 10))
    }

    @MainActor
    private func openNewGame(_ app: XCUIApplication) {
        let newGame = app.buttons["New Game"].firstMatch
        XCTAssertTrue(newGame.waitForExistence(timeout: 10))
        newGame.tap()
    }

    @MainActor
    private func addGameName(_ app: XCUIApplication, _ name: String) {
        app.buttons["New Game Name"].tap()
        let field = app.textFields["Game name (e.g. Scopa, Briscola)"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(name)
        app.buttons["Save"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts[name].waitForExistence(timeout: 5))
    }
}
