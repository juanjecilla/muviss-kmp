import XCTest

/// The smoke path a reviewer walks first: launch, get past the first-run
/// introduction, reach Search, open Settings.
///
/// Offline-safe by construction: nothing here waits for TMDB, so a missing
/// network or a rate-limited key cannot fail it. Compose iOS exposes a
/// `Modifier.testTag` as the accessibility identifier, so the Settings check
/// is by identifier. The tab taps use the English label, which is why the
/// launch forces English.
final class MuvissSmokeUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testLaunchSkipsOnboardingAndOpensSettings() throws {
        let app = launch()

        // A fresh install opens the first-run introduction (EPIC 47). Skip it;
        // an install that has already finished it lands on Search directly.
        let skip = app.element(labelled: "Skip")
        if skip.waitForExistence(timeout: 10) {
            skip.tap()
        }

        XCTAssertTrue(app.element(labelled: "Search").waitForExistence(timeout: 45), "Search was not reached")

        let settings = app.element(labelled: "Settings")
        XCTAssertTrue(settings.waitForExistence(timeout: 10), "Settings tab was not found")
        settings.tap()
        // A cold CI simulator can take well over ten seconds to draw the first Settings frame (the
        // test failed there, not in a bug, #502's run): wait for it generously.
        XCTAssertTrue(
            app.element(identifiedAs: "settings_version_row").waitForExistence(timeout: 45),
            "Settings did not open"
        )
    }

    /// Settings > Export presents the system share sheet (#415, #186). On an iPad that sheet is a popover and
    /// UIKit throws at present time unless it is anchored, which killed the app before the anchor existed;
    /// on an iPhone it is a bottom sheet. Either way the app must still be running with the sheet up.
    func testExportPresentsTheShareSheetWithoutCrashing() throws {
        let app = launch()
        let skip = app.element(labelled: "Skip")
        if skip.waitForExistence(timeout: 10) { skip.tap() }
        XCTAssertTrue(app.element(labelled: "Search").waitForExistence(timeout: 45), "Search was not reached")
        app.element(labelled: "Settings").tap()
        XCTAssertTrue(app.element(identifiedAs: "settings_version_row").waitForExistence(timeout: 45), "Settings did not open")

        let export = app.element(labelled: "Export")
        for _ in 0..<6 where !export.exists { app.swipeUp() }
        XCTAssertTrue(export.waitForExistence(timeout: 10), "the Export button was not found")
        export.tap()

        let sheet = app.otherElements["ActivityListView"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 15), "the share sheet did not appear")
        XCTAssertEqual(app.state, .runningForeground, "the app died presenting the share sheet")
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        return app
    }
}

private extension XCUIApplication {
    /// The element a Compose `testTag` became (`identifier` on iOS).
    func element(identifiedAs identifier: String) -> XCUIElement {
        descendants(matching: .any)[identifier]
    }

    /// Any element whose accessibility label is exactly `label`. A Compose
    /// button and the text inside it can both carry the label, so no type is
    /// asked for; `firstMatch` takes the one that is on screen.
    func element(labelled label: String) -> XCUIElement {
        descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", label))
            .firstMatch
    }
}
