//
//  ScoringSheetUITests.swift
//  ScoreCardUITests
//
//  Regression test for the swipe-to-delete crash in the Add Points sheet
//  (UICollectionView "invalid number of items in section" on iOS 18 devices).
//  Deletes entries one by one — including the last one, which also removes the
//  Entries section — and mixes saved and unsaved entries, since a SwiftData
//  save changes unsaved entries' persistentModelIDs mid-flight.
//

import XCTest

final class ScoringSheetUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testSwipeDeletingScoreEntriesDoesNotCrash() throws {
        // The launch-screenshot tests iterate UI configurations and can leave
        // the simulator in landscape, which pushes the controls this test
        // needs below the fold — pin portrait.
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-uitesting"]   // clean in-memory store
        app.launch()

        // --- Create a game with two inline players ---
        let newGame = app.buttons["New Game"].firstMatch
        XCTAssertTrue(newGame.waitForExistence(timeout: 10))
        newGame.tap()
        addPlayerInline(app, name: "Alice")
        addPlayerInline(app, name: "Bob")

        app.buttons["New Game Name"].tap()
        let nameField = app.textFields["Game name (e.g. Scopa, Briscola)"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 5))
        nameField.tap()
        nameField.typeText("Delete Repro")
        app.buttons["Save"].tap()

        let next = app.buttons["Next"]
        XCTAssertTrue(next.waitForExistence(timeout: 5))
        next.tap()
        let start = app.buttons["Start Game"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        start.tap()

        // --- Open the scoreboard and the Add Points sheet ---
        let gameRow = app.staticTexts["Delete Repro"].firstMatch
        XCTAssertTrue(gameRow.waitForExistence(timeout: 10))
        gameRow.tap()

        let more = app.buttons["scoreOptions"].firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 10))
        more.tap()
        XCTAssertTrue(app.navigationBars["Add Points"].waitForExistence(timeout: 5))

        // --- Score two entries from the sheet ---
        app.buttons["sheetAdd2"].tap()
        app.buttons["sheetAdd3"].tap()
        assertSheetTotal(app, is: "5")

        // --- Delete the newest entry (+3) ---
        swipeDeleteEntry(app, labeled: "+3")
        assertSheetTotal(app, is: "2")

        // --- Add another entry, so saved and unsaved entries coexist ---
        // (+5, not +1: the Custom stepper shows a "+1" static text that would
        // shadow the entry row in the accessibility lookup.)
        app.buttons["sheetAdd5"].tap()
        assertSheetTotal(app, is: "7")
        swipeDeleteEntry(app, labeled: "+5")
        assertSheetTotal(app, is: "2")

        // --- Delete the last entry: the Entries section itself goes away ---
        swipeDeleteEntry(app, labeled: "+2")
        assertSheetTotal(app, is: "0")

        // The sheet (and app) must still be alive and interactive.
        XCTAssertTrue(app.navigationBars["Add Points"].exists)
        app.buttons["Done"].tap()
        XCTAssertTrue(app.buttons["scoreOptions"].firstMatch.waitForExistence(timeout: 5),
                      "Scoreboard should still be alive after deleting entries")
    }

    /// The real-world crash flow on iOS 18: play to a target across several
    /// hands, reach it, decline ending the game, then correct the over-target
    /// score by swipe-deleting entries in the Add Points sheet. The deletion
    /// flips `reachedTarget`, which removes the target banner *section* from
    /// the scoreboard's List behind the sheet.
    @MainActor
    func testDeletingEntriesAfterDecliningTargetEndDoesNotCrash() throws {
        XCUIDevice.shared.orientation = .portrait   // see the swipe test
        let app = XCUIApplication()
        app.launchArguments += ["-uitesting"]   // clean in-memory store
        app.launch()

        // --- Create a game with two players and the default target (11) ---
        let newGame = app.buttons["New Game"].firstMatch
        XCTAssertTrue(newGame.waitForExistence(timeout: 10))
        newGame.tap()
        addPlayerInline(app, name: "Alice")
        addPlayerInline(app, name: "Bob")

        app.buttons["New Game Name"].tap()
        let nameField = app.textFields["Game name (e.g. Scopa, Briscola)"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 5))
        nameField.tap()
        nameField.typeText("Target Repro")
        app.buttons["Save"].tap()

        // Tap the switch itself (trailing edge), not the row label — a center
        // tap on the SwiftUI Toggle row doesn't reliably flip it — and verify
        // the flip so a silently ignored tap can't devolve into an open-ended
        // game that never prompts.
        let targetToggle = app.switches["Play to a target score"].firstMatch
        XCTAssertTrue(targetToggle.waitForExistence(timeout: 5))
        targetToggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        let toggleOn = NSPredicate(format: "value == '1'")
        XCTAssertEqual(XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: toggleOn, object: targetToggle)], timeout: 5),
                       .completed, "The target toggle should turn on")

        let next = app.buttons["Next"]
        XCTAssertTrue(next.waitForExistence(timeout: 5))
        next.tap()
        let start = app.buttons["Start Game"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        start.tap()

        let gameRow = app.staticTexts["Target Repro"].firstMatch
        XCTAssertTrue(gameRow.waitForExistence(timeout: 10))
        gameRow.tap()

        // --- Score Bob to 13 across three hands (5 + 5 + 3, target 11) ---
        // The dealer card can also show "Bob"; require the scoring control so
        // we match Bob's scoreboard row, not the dealer card.
        let bobCell = app.cells
            .containing(.staticText, identifier: "Bob")
            .containing(.button, identifier: "scoreOptions")
            .firstMatch
        XCTAssertTrue(bobCell.waitForExistence(timeout: 10))

        bobCell.buttons["+5"].tap()
        tapNextHand(app)
        bobCell.buttons["+5"].tap()
        tapNextHand(app)
        bobCell.buttons["+3"].tap()

        // --- Target reached: decline ending the game (board locks) ---
        // iOS 18 renders the confirmation dialog as an action sheet with a
        // "Not Yet" cancel button; iOS 26 renders an anchored popover that
        // omits the cancel action — there, dismissing the popover by tapping
        // outside it IS the decline path.
        XCTAssertTrue(app.staticTexts["End the game?"].firstMatch.waitForExistence(timeout: 5),
                      "Reaching the target should prompt to end the game")
        let notYet = app.buttons["Not Yet"]
        if notYet.waitForExistence(timeout: 2) {
            notYet.tap()
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.95)).tap()
        }

        // --- Correct the score from the Add Points sheet ---
        let more = bobCell.buttons["scoreOptions"].firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 5))
        more.tap()
        XCTAssertTrue(app.navigationBars["Add Points"].waitForExistence(timeout: 5))

        // Deleting +3 drops Bob to 10 < 11: the target banner section behind
        // the sheet disappears — the historical crash point.
        swipeDeleteEntry(app, labeled: "+3")
        assertSheetTotal(app, is: "10")

        // Keep going: delete the remaining entries, including the last one.
        swipeDeleteEntry(app, labeled: "+5")
        assertSheetTotal(app, is: "5")
        swipeDeleteEntry(app, labeled: "+5")
        assertSheetTotal(app, is: "0")

        XCTAssertTrue(app.navigationBars["Add Points"].exists)
        app.buttons["Done"].tap()
        XCTAssertTrue(app.buttons["scoreOptions"].firstMatch.waitForExistence(timeout: 5),
                      "Scoreboard should still be alive after correcting the score")
    }

    /// Advances to the next hand from the scoreboard.
    @MainActor
    private func tapNextHand(_ app: XCUIApplication) {
        let nextHand = app.buttons["Next Hand"].firstMatch
        XCTAssertTrue(nextHand.waitForExistence(timeout: 5))
        XCTAssertTrue(nextHand.isEnabled, "Next Hand should be armed after scoring")
        nextHand.tap()
    }

    /// Swipes left on the entry row showing `label` (a static text, distinct
    /// from the quick-add *buttons* with the same titles) and taps Delete.
    /// The sheet opens at the medium detent with the Entries section below the
    /// fold, and the lazy List doesn't create off-screen rows — so scroll up
    /// (which also expands the sheet) until the row exists and is hittable.
    @MainActor
    private func swipeDeleteEntry(_ app: XCUIApplication, labeled label: String) {
        let text = app.staticTexts[label].firstMatch
        var swipes = 0
        while !(text.exists && text.isHittable) && swipes < 8 {
            app.swipeUp()
            swipes += 1
        }
        XCTAssertTrue(text.waitForExistence(timeout: 5), "Entry row \(label) should exist")
        // Swipe the full-width cell, not the narrow text: a swipe on the text's
        // own small frame doesn't travel far enough to open the swipe actions.
        let cell = app.cells.containing(.staticText, identifier: label).firstMatch
        (cell.exists ? cell : text).swipeLeft()
        // A partial swipe reveals a Delete button; a full swipe deletes
        // immediately without one. Accept both — the caller asserts the total.
        let delete = app.buttons["Delete"].firstMatch
        if delete.waitForExistence(timeout: 2) {
            delete.tap()
        }
    }

    /// Waits until the sheet's big total shows `expected`.
    @MainActor
    private func assertSheetTotal(_ app: XCUIApplication, is expected: String) {
        let total = app.staticTexts["sheetTotal"]
        let predicate = NSPredicate(format: "label == %@", expected)
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: total)
        XCTAssertEqual(XCTWaiter().wait(for: [expectation], timeout: 5), .completed,
                       "Sheet total should become \(expected)")
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
}
