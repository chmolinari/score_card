//
//  HelpUITests.swift
//  ScoreCardUITests
//
//  End-to-end coverage for the in-app help: that Settings actually reaches it,
//  that the index lists the topics, and that a topic opens and comes back.
//
//  It deliberately pins the entry point and the navigation, not the prose. The
//  words are specified in docs/help-content.md and asserted in the unit tests;
//  restating them here would only mean editing two places for every wording fix.
//

import XCTest

final class HelpUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// Settings → "How to Use ScoreCard" → the index → the first topic → back.
    @MainActor
    func testHelpIsReachableFromSettingsAndOpensATopic() throws {
        // The launch-screenshot tests iterate UI configurations and can leave
        // the simulator in landscape, which pushes controls below the fold.
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-uitesting"]   // clean in-memory store
        app.launch()

        app.tabBars.buttons["Settings"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10),
                      "The Settings tab should open")

        // --- The entry point ---
        let helpEntry = app.buttons["How to Use ScoreCard"].firstMatch
        XCTAssertTrue(helpEntry.waitForExistence(timeout: 10),
                      "Settings should offer How to Use ScoreCard")
        helpEntry.tap()

        // --- The index ---
        XCTAssertTrue(app.navigationBars["Help"].waitForExistence(timeout: 10),
                      "Tapping the entry should push the help index")
        let firstTopic = app.buttons["Getting Started"].firstMatch
        XCTAssertTrue(firstTopic.waitForExistence(timeout: 5),
                      "The index should list the first topic")

        // --- One topic's page ---
        firstTopic.tap()
        XCTAssertTrue(app.navigationBars["Getting Started"].waitForExistence(timeout: 10),
                      "Tapping a topic should push its page")

        // --- ...and back to the index it came from ---
        tapBack(app, from: "Getting Started")
        XCTAssertTrue(app.navigationBars["Help"].waitForExistence(timeout: 10),
                      "Back should return to the help index")
    }

    // MARK: - Element helpers

    /// Taps the leading (back) button of the navigation bar with `title`.
    /// Scoping to that bar keeps the lookup off the screen underneath.
    @MainActor
    private func tapBack(_ app: XCUIApplication, from title: String) {
        let bar = app.navigationBars[title]
        XCTAssertTrue(bar.waitForExistence(timeout: 10), "Expected to be on \(title)")
        let back = bar.buttons.element(boundBy: 0)
        XCTAssertTrue(back.waitForExistence(timeout: 5), "\(title) should have a back button")
        back.tap()
    }
}
