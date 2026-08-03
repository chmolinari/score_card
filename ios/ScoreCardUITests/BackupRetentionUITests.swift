//
//  BackupRetentionUITests.swift
//  ScoreCardUITests
//
//  The retention setting's two edges: the number can never reach zero, and
//  lowering it offers to delete backups rather than just doing it.
//
//  Asserted by *attempting the bypass*, per the house convention — the number is
//  actually driven to the floor and the prompt actually dismissed, then the
//  backup list is checked to still hold its rows. Asserting that a dialog
//  appeared would pass even if the files had already gone.
//
//  Nothing here assumes a starting value: preferences outlive a relaunch on the
//  simulator, so each test establishes the state it needs.
//
//  Two things are deliberately *not* driven through the UI. That the number
//  defaults to ten, and that it can never reach zero, are pinned by the unit
//  tests instead (`clampCount`, `storedCount`, and `surplus` with a limit of
//  zero or less). Both were tried here first and proved unreliable: XCUITest
//  drops taps on a SwiftUI stepper, so a run ends at an arbitrary number rather
//  than at the bound, and a test that fails for that reason says nothing about
//  the app. The bound is a pure rule and belongs where it can be asserted
//  exactly.
//

import XCTest

final class BackupRetentionUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// Seeded, because "Back Up Now" is disabled while the store is empty.
    private func launch() -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait   // launch tests may leave landscape
        let app = XCUIApplication()
        app.launchArguments += ["-uitesting", "-seedSampleData"]
        app.launch()
        return app
    }

    /// Scrolls Settings until `element` can be tapped, starting from the top so
    /// the search does not depend on where a previous step left the list.
    @MainActor
    private func reveal(_ element: XCUIElement, in app: XCUIApplication, maxSwipes: Int = 10) {
        for _ in 0..<maxSwipes where !element.isHittable { app.swipeDown() }
        var swipes = 0
        while !element.isHittable && swipes < maxSwipes {
            app.swipeUp()
            swipes += 1
        }
        XCTAssertTrue(element.isHittable, "\(element) never scrolled into view")
    }

    @MainActor
    private func openSettings(_ app: XCUIApplication) {
        app.tabBars.buttons["Settings"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
    }

    @MainActor
    private func retentionRow(_ app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Keep backups")).firstMatch
    }

    /// Picks a value from the pushed list. One move, unlike the +/- buttons
    /// this replaced, which XCUITest drove unreliably — taps were dropped and a
    /// run ended at an arbitrary number.
    @MainActor
    private func setKept(_ app: XCUIApplication, to value: String) {
        let row = retentionRow(app)
        reveal(row, in: app)
        row.tap()
        let option = app.buttons[value].firstMatch
        XCTAssertTrue(option.waitForExistence(timeout: 10), "\(value) should be offered")
        // Choosing pops the list by itself; no back tap to make.
        option.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10),
                      "Choosing a value should return to Settings")
    }

    @MainActor
    private func makeABackup(_ app: XCUIApplication) {
        let backUp = app.buttons["Back Up Now"].firstMatch
        reveal(backUp, in: app)
        XCTAssertTrue(backUp.isEnabled, "Back Up Now should be available with data present")
        backUp.tap()
        let ok = app.buttons["OK"].firstMatch
        XCTAssertTrue(ok.waitForExistence(timeout: 30), "Backing up should report completion")
        ok.tap()
    }

    // MARK: - Lowering asks first

    @MainActor
    func testLoweringAsksBeforeDeletingAndCancellingKeepsTheBackups() throws {
        let app = launch()
        openSettings(app)

        // Raise the limit first — raising never prompts — so two fresh backups
        // both survive and there is something for the drop to propose.
        setKept(app, to: "5")

        makeABackup(app)
        makeABackup(app)

        // One move of the wheel, and the question is asked once for the whole
        // change rather than once per number passed on the way down.
        setKept(app, to: "1 (only the latest)")

        let confirm = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Delete ")
        ).firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 10),
                      "Lowering the number must ask before removing anything")

        dismissConfirmation(app)

        // Prove nothing was deleted by counting the list, not by trusting the
        // dialog to have been harmless.
        let restore = app.buttons["Restore from Backup…"].firstMatch
        reveal(restore, in: app)
        restore.tap()
        XCTAssertTrue(app.navigationBars["Restore"].waitForExistence(timeout: 10),
                      "The backup list should open")
        XCTAssertGreaterThanOrEqual(app.cells.count, 2,
                                    "Cancelling must leave both backups in place")
    }

    /// SwiftUI renders a `confirmationDialog` as a popover here, which drops the
    /// cancel-role button in favour of a tap-outside dismiss region.
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
}
