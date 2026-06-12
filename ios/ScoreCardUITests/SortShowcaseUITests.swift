//
//  SortShowcaseUITests.swift
//  ScoreCardUITests
//
//  Drives the real app (with the seeded sample store) to capture screenshots of
//  the new Players/Teams sort menu in action. Not an assertion-heavy test — its
//  job is to navigate and attach screenshots that show the feature working.
//

import XCTest

final class SortShowcaseUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testCaptureSortMenuScreenshots() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-uitesting", "-seedSampleData"]
        app.launch()

        // MARK: Players

        app.tabBars.buttons["Players"].tap()
        XCTAssertTrue(app.staticTexts["Alice"].waitForExistence(timeout: 10),
                      "Seeded players should be listed")
        snap("01-players-name-ascending")

        // Open the sort menu and capture the four options.
        let playersSort = app.buttons["Sort"].firstMatch
        XCTAssertTrue(playersSort.waitForExistence(timeout: 5), "Sort menu button should exist")
        playersSort.tap()
        XCTAssertTrue(app.buttons["Wins (high to low)"].waitForExistence(timeout: 5),
                      "Sort menu should offer a Wins option")
        snap("02-players-sort-menu")

        // Sort by wins — Alice (1 win) should rise to the top.
        app.buttons["Wins (high to low)"].tap()
        XCTAssertTrue(app.staticTexts["Alice"].waitForExistence(timeout: 5))
        snap("03-players-wins-descending")

        // Sort by name, descending — Dave should now lead.
        playersSort.tap()
        app.buttons["Name (Z\u{2013}A)"].tap()   // en dash to match the label exactly
        snap("04-players-name-descending")

        // MARK: Teams

        app.tabBars.buttons["Teams"].tap()
        XCTAssertTrue(app.staticTexts["The Aces"].waitForExistence(timeout: 5),
                      "Seeded teams should be listed")
        snap("05-teams-name-ascending")

        let teamsSort = app.buttons["Sort"].firstMatch
        XCTAssertTrue(teamsSort.waitForExistence(timeout: 5))
        teamsSort.tap()
        XCTAssertTrue(app.buttons["Wins (high to low)"].waitForExistence(timeout: 5))
        snap("06-teams-sort-menu")

        // Reverse the team name order so the screenshot differs from the default.
        app.buttons["Name (Z\u{2013}A)"].tap()
        snap("07-teams-name-descending")
    }

    /// Captures the full screen (so menu overlays are included) and attaches it.
    @MainActor
    private func snap(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
