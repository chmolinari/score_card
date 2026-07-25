//
//  GameEditUITests.swift
//  ScoreCardUITests
//
//  End-to-end coverage for correcting a closed game's scores: the mandatory
//  motivation gate, the score-only edit, and the "Edited" badge that has to show
//  on the game's card afterwards — plus the mirror case, that walking through
//  the editor without touching a score leaves no trace at all.
//

import XCTest

final class GameEditUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// Editing a closed game's score marks it: the reason cannot be skipped, the
    /// corrected total is saved, and the game's card gains the "Edited" badge.
    @MainActor
    func testEditingClosedGameScoreMarksItAsEdited() throws {
        // The launch-screenshot tests iterate UI configurations and can leave
        // the simulator in landscape, which pushes controls below the fold.
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-uitesting"]   // clean in-memory store
        app.launch()

        registerPastGame(app, named: "Edited Match", scores: ["21", "15"])
        openHistoryGame(app, named: "Edited Match")

        // --- Step 1: the motivation is mandatory ---
        let edit = app.buttons["editGameButton"]
        XCTAssertTrue(edit.waitForExistence(timeout: 10), "A closed game should offer Edit Scores")
        edit.tap()

        let cont = app.buttons["editMotivationContinue"]
        XCTAssertTrue(cont.waitForExistence(timeout: 10), "The editor should open on the reason step")
        XCTAssertFalse(cont.isEnabled, "Continue must stay disabled while no reason is typed")

        let reasonField = anyElement(app, "editMotivationField")
        XCTAssertTrue(reasonField.waitForExistence(timeout: 5), "The reason field should be present")
        reasonField.tap()
        XCTAssertTrue(app.keyboards.element.waitForExistence(timeout: 5),
                      "Tapping the reason field should raise the keyboard")
        app.typeText("Miscounted the last hand")

        waitUntilEnabled(cont, "Continue should enable once a reason is typed")
        cont.tap()

        // --- Step 2: only the scores can be changed ---
        XCTAssertTrue(app.navigationBars["Edit Scores"].waitForExistence(timeout: 10),
                      "Continue should push the scores step")
        let save = app.buttons["editSaveButton"]
        XCTAssertTrue(save.waitForExistence(timeout: 5))
        XCTAssertFalse(save.isEnabled, "Save should be disabled before anything is changed")

        // Deliberately left focused, with the keyboard still up: tapping Save in
        // the navigation bar must commit what is on screen, not the total the
        // field held before it was typed into.
        replaceScore(app, at: 0, with: "30")

        waitUntilEnabled(save, "Save should enable once a total actually differs")
        save.tap()

        // --- The correction is recorded on the game itself ---
        XCTAssertTrue(app.buttons["editGameButton"].waitForExistence(timeout: 10),
                      "Saving should return to the game's detail screen")
        XCTAssertTrue(app.staticTexts["30"].waitForExistence(timeout: 10),
                      "The corrected total should be showing in the final standings")
        XCTAssertFalse(app.staticTexts["21"].exists,
                       "The superseded total should be gone from the final standings")

        // The Edit History section sits at the bottom and the lazy List doesn't
        // build off-screen rows, so scroll it up before looking for it.
        let reasonRow = app.staticTexts["Miscounted the last hand"]
        scrollIntoView(reasonRow, in: app)
        XCTAssertTrue(reasonRow.exists,
                      "The saved edit and its motivation should be logged on the detail screen")

        // --- ...and the card in the list carries the badge ---
        tapBack(app, from: "Edited Match")
        XCTAssertTrue(app.staticTexts["Edited Match"].waitForExistence(timeout: 10),
                      "Back on the games list")
        XCTAssertTrue(anyElement(app, "editedBadge").waitForExistence(timeout: 5),
                      "An edited game's card must show the Edited badge")
    }

    /// Walking through the editor without changing a score leaves no trace: Save
    /// never arms, and cancelling out adds neither an edit record nor a badge.
    @MainActor
    func testCancellingEditWithoutScoreChangeLeavesNoTrace() throws {
        XCUIDevice.shared.orientation = .portrait   // see the test above
        let app = XCUIApplication()
        app.launchArguments += ["-uitesting"]   // clean in-memory store
        app.launch()

        registerPastGame(app, named: "Untouched Match", scores: ["21", "15"])
        openHistoryGame(app, named: "Untouched Match")

        let edit = app.buttons["editGameButton"]
        XCTAssertTrue(edit.waitForExistence(timeout: 10))
        edit.tap()

        let cont = app.buttons["editMotivationContinue"]
        XCTAssertTrue(cont.waitForExistence(timeout: 10))
        XCTAssertFalse(cont.isEnabled, "Continue must stay disabled while no reason is typed")

        let reasonField = anyElement(app, "editMotivationField")
        XCTAssertTrue(reasonField.waitForExistence(timeout: 5))
        reasonField.tap()
        XCTAssertTrue(app.keyboards.element.waitForExistence(timeout: 5))
        app.typeText("Just having a look")

        waitUntilEnabled(cont, "Continue should enable once a reason is typed")
        cont.tap()

        // Scores step reached, but nothing is touched.
        XCTAssertTrue(app.navigationBars["Edit Scores"].waitForExistence(timeout: 10))
        XCTAssertTrue(anyElement(app, "editScore0").waitForExistence(timeout: 5),
                      "The scores step should list the competitors")
        let save = app.buttons["editSaveButton"]
        XCTAssertTrue(save.waitForExistence(timeout: 5))
        XCTAssertFalse(save.isEnabled,
                       "Save must stay disabled while the final score is unchanged")

        // Back out of the scores step, then cancel the whole editor. (Cancel
        // lives on the reason step's toolbar, not the scores step's.)
        tapBack(app, from: "Edit Scores")
        let cancel = app.buttons["Cancel"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 5), "The reason step should offer Cancel")
        cancel.tap()

        // Nothing was written: no edit log on the detail screen...
        XCTAssertTrue(app.buttons["editGameButton"].waitForExistence(timeout: 10),
                      "Cancelling should return to the game's detail screen")
        // Scroll to where the section would be first. Asserting absence without
        // scrolling would pass even if the section existed, since a lazy List
        // keeps below-the-fold rows out of the accessibility tree entirely.
        scrollToBottom(app)
        XCTAssertFalse(app.staticTexts["Edit History"].exists,
                       "An abandoned edit must not be logged")
        XCTAssertFalse(app.staticTexts["Just having a look"].exists,
                       "An abandoned edit's reason must not be recorded")

        // ...and no badge on the card.
        tapBack(app, from: "Untouched Match")
        XCTAssertTrue(app.staticTexts["Untouched Match"].waitForExistence(timeout: 10),
                      "Back on the games list")
        XCTAssertFalse(anyElement(app, "editedBadge").exists,
                       "A game whose score never changed must not be badged as edited")
    }

    // MARK: - Flow helpers

    /// Creates a *closed* game cheaply through the Register Past Game flow:
    /// two inline players, a game name, and the given final scores in order.
    @MainActor
    private func registerPastGame(_ app: XCUIApplication,
                                  named name: String,
                                  scores: [String]) {
        let register = app.buttons["Register Past Game"].firstMatch
        XCTAssertTrue(register.waitForExistence(timeout: 10))
        register.tap()

        addPlayerInline(app, name: "Alice")
        addPlayerInline(app, name: "Bob")
        XCTAssertTrue(app.staticTexts["1. Alice"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["2. Bob"].waitForExistence(timeout: 5))

        app.buttons["New Game Name"].tap()
        let nameField = app.textFields["Game name (e.g. Scopa, Briscola)"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 5))
        nameField.tap()
        nameField.typeText(name)
        app.buttons["Save"].tap()
        XCTAssertTrue(app.staticTexts[name].waitForExistence(timeout: 5))

        let next = app.buttons["Next"]
        XCTAssertTrue(next.waitForExistence(timeout: 5))
        next.tap()

        // Details step: each field commits when the next one takes focus.
        let firstScore = app.textFields["registerScore0"]
        XCTAssertTrue(firstScore.waitForExistence(timeout: 5), "Details step should appear")
        for (index, score) in scores.enumerated() {
            let field = app.textFields["registerScore\(index)"]
            XCTAssertTrue(field.waitForExistence(timeout: 5))
            field.tap()
            field.typeText(score)
        }
        // Commits the last score field.
        let location = app.textFields["Location (optional)"]
        XCTAssertTrue(location.waitForExistence(timeout: 5))
        location.tap()

        let saveGame = app.buttons["Save Game"]
        waitUntilEnabled(saveGame, "Save should enable once all final scores are set")
        saveGame.tap()

        XCTAssertTrue(app.staticTexts["History"].waitForExistence(timeout: 10),
                      "A registered game should be filed under History")
    }

    /// Opens a closed game's detail screen from the History section.
    @MainActor
    private func openHistoryGame(_ app: XCUIApplication, named name: String) {
        let row = app.staticTexts[name].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "\(name) should be listed")
        row.tap()
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

    /// Clears one competitor's total in the score editor and types a new one.
    ///
    /// Leaves the field focused unless `thenDismissKeyboard` is set: a typed
    /// total must reach the editor's state on the keystroke, not when the field
    /// resigns focus, because Save sits in the navigation bar and tapping it
    /// does not resign focus first.
    @MainActor
    private func replaceScore(_ app: XCUIApplication,
                              at index: Int,
                              with newTotal: String,
                              thenDismissKeyboard: Bool = false) {
        let field = anyElement(app, "editScore\(index)")
        XCTAssertTrue(field.waitForExistence(timeout: 5), "Score field \(index) should exist")

        // Tap near the trailing edge: the field is right-aligned inside a narrow
        // frame, so a centre tap can land left of the digits and park the caret
        // in front of them, where backspace does nothing.
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        XCTAssertTrue(app.keyboards.element.waitForExistence(timeout: 5),
                      "Tapping a score field should raise the keyboard")

        // More deletes than the total can possibly be long; extra backspaces on
        // an empty field are no-ops.
        app.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 10))
        app.typeText(newTotal)

        guard thenDismissKeyboard else { return }
        let done = app.buttons["editScoreDone"]
        XCTAssertTrue(done.waitForExistence(timeout: 5), "The keyboard toolbar should offer Done")
        done.tap()
    }

    // MARK: - Element helpers

    /// Looks an identifier up across every element type. SwiftUI decides on its
    /// own whether a control surfaces as a text field, a text view or a plain
    /// container (a vertical-axis `TextField`, or a `Label` collapsed with
    /// `accessibilityElement(children: .combine)`), so the tests don't pin one.
    @MainActor
    private func anyElement(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    /// Taps the leading (back) button of the navigation bar with `title`.
    /// Scoping to that bar keeps the lookup off the screen underneath a sheet.
    @MainActor
    private func tapBack(_ app: XCUIApplication, from title: String) {
        let bar = app.navigationBars[title]
        XCTAssertTrue(bar.waitForExistence(timeout: 10), "Expected to be on \(title)")
        let back = bar.buttons.element(boundBy: 0)
        XCTAssertTrue(back.waitForExistence(timeout: 5), "\(title) should have a back button")
        back.tap()
    }

    /// Swipes up until the element materialises. SwiftUI's lazy `List` doesn't
    /// instantiate off-screen rows, so anything below the fold is missing from
    /// the accessibility tree until it's scrolled in.
    @MainActor
    private func scrollIntoView(_ element: XCUIElement,
                                in app: XCUIApplication,
                                maxSwipes: Int = 6) {
        var swipes = 0
        while !element.exists && swipes < maxSwipes {
            app.swipeUp()
            swipes += 1
        }
    }

    /// Swipes to the bottom of the current screen, so that asserting something
    /// is *absent* isn't satisfied merely by it being below the fold.
    @MainActor
    private func scrollToBottom(_ app: XCUIApplication, swipes: Int = 4) {
        for _ in 0..<swipes { app.swipeUp() }
    }

    /// Waits for a control to become enabled, rather than assuming SwiftUI has
    /// already re-evaluated the state that arms it.
    @MainActor
    private func waitUntilEnabled(_ element: XCUIElement, _ message: String) {
        let predicate = NSPredicate(format: "isEnabled == true")
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        XCTAssertEqual(XCTWaiter().wait(for: [expectation], timeout: 10), .completed, message)
    }
}
