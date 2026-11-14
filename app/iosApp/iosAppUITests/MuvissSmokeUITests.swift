import XCTest

/// The smoke path a reviewer walks first: launch, get past the first-run
/// introduction, reach Search, open Settings.
///
/// Offline-safe by construction: nothing here waits for TMDB, so a missing
/// network or a rate-limited key cannot fail it. Compose iOS exposes a
/// `Modifier.testTag` as the accessibility identifier, so the Settings check
/// is by identifier. The tab taps use the English label, which is why the
/// launch forces English.
///
/// Readiness is asked as a predicate, never by reading `isHittable` directly.
/// A Compose element that has not been laid out yet has no activation point,
/// and reading `isHittable` on it records a test failure ("Failed to determine
/// hittability") instead of answering "not yet". That was the most common way
/// this suite failed on CI (#784).
final class MuvissSmokeUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testLaunchSkipsOnboardingAndOpensSettings() throws {
        let app = launch()
        reachSearch(app)
        openSettings(app)
    }

    /// Settings > Export presents the system share sheet (#415, #186). On an iPad that sheet is a popover and
    /// UIKit throws at present time unless it is anchored, which killed the app before the anchor existed;
    /// on an iPhone it is a bottom sheet. Either way the app must still be running with the sheet up.
    func testExportPresentsTheShareSheetWithoutCrashing() throws {
        let app = launch()
        reachSearch(app)
        openSettings(app)

        // Export sits below the fold. Scroll until it is found and tappable. Each check is a predicate
        // expectation with a short wait, so a row that is not on screen yet is "not yet", not a failure.
        let export = app.element(labelled: "Export")
        for _ in 0..<8 {
            if XCTWaiter.wait(for: [tappable(export)], timeout: 2) == .completed { break }
            app.swipeUp()
        }
        XCTAssertEqual(XCTWaiter.wait(for: [tappable(export)], timeout: 10), .completed, "the Export button never scrolled on screen")
        export.tap()

        let sheet = app.otherElements["ActivityListView"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 15), "the share sheet did not appear")
        XCTAssertEqual(app.state, .runningForeground, "the app died presenting the share sheet")
    }

    /// Gets past the first-run introduction (EPIC 47) and waits for Search. A fresh install opens the
    /// introduction; an install that has finished it lands on Search. Which one is showing cannot be known
    /// at launch: a cold simulator can take well over ten seconds to draw the first frame. The old code
    /// gave Skip ten seconds, and when that window closed first it never tapped Skip, then waited 45 seconds
    /// for a Search the introduction was holding back (#784, "Search was not reached"). So this keeps
    /// tapping Skip whenever it is tappable, until Search exists or the whole start has had its time.
    private func reachSearch(_ app: XCUIApplication) {
        let skip = app.element(labelled: "Skip")
        let search = app.element(labelled: "Search")
        let deadline = Date().addingTimeInterval(120)
        while !search.exists, Date() < deadline {
            // A fresh expectation per wait: XCTest aborts ("expectations can only be waited on once")
            // if the same one is waited on again, which the loop does on every pass that is not yet ready.
            if XCTWaiter.wait(for: [tappable(skip)], timeout: 1) == .completed {
                skip.tap()
            }
            Thread.sleep(forTimeInterval: 0.5)
        }
        XCTAssertTrue(search.waitForExistence(timeout: 5), "Search was not reached")
    }

    /// Taps the Settings tab once it can take the tap, then waits for the screen. A cold CI simulator can
    /// take well over ten seconds to draw the first Settings frame (the test failed there, not in a bug,
    /// #502's run), so both waits are generous.
    private func openSettings(_ app: XCUIApplication) {
        let settings = app.element(labelled: "Settings")
        XCTAssertEqual(XCTWaiter.wait(for: [tappable(settings)], timeout: 45), .completed, "Settings tab was not ready")
        settings.tap()
        XCTAssertTrue(
            app.element(identifiedAs: "settings_version_row").waitForExistence(timeout: 45),
            "Settings did not open"
        )
    }

    /// Passes once [element] exists and can take a tap. Asked through a predicate, so reading it never
    /// records a failure while the element is still being laid out.
    private func tappable(_ element: XCUIElement) -> XCTNSPredicateExpectation {
        XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND hittable == true"), object: element)
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
