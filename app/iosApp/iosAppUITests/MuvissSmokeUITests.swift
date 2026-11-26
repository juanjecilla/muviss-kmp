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
/// Readiness is judged by existence and frame, never by `isHittable` or a
/// `hittable` predicate. XCTest records "Failed to determine hittability" as a
/// test failure, and it does so while a cold first frame is still laying out
/// (#784), so a wait that only asks "is it hittable yet?" fails the run instead
/// of waiting for the answer.
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

        // Export sits below the fold. Compose may or may not expose an off-screen row to accessibility,
        // so scroll until it is both found and on screen rather than relying on either alone.
        let export = app.element(labelled: "Export")
        for _ in 0..<8 where !isVisibleAboveBottomBar(export, in: app) { app.swipeUp() }
        XCTAssertTrue(waitForVisibleAboveBottomBar(export, in: app, timeout: 10), "the Export button never scrolled on screen")
        tap(export)

        let sheet = app.otherElements["ActivityListView"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 15), "the share sheet did not appear")
        XCTAssertEqual(app.state, .runningForeground, "the app died presenting the share sheet")
    }

    /// Gets past the first-run introduction (EPIC 47) and waits for Search. A fresh install opens the
    /// introduction, whose Skip button can take a long time to appear on a cold simulator: a single
    /// ten-second wait for it missed on a slow first frame, and then Search never came (#784). So Skip is
    /// tapped for as long as it is on screen, and the loop ends as soon as Search is reached. An install
    /// that has already finished the introduction lands on Search directly.
    private func reachSearch(_ app: XCUIApplication) {
        let skip = app.element(labelled: "Skip")
        let search = app.element(labelled: "Search")
        let deadline = Date().addingTimeInterval(120)
        while !search.exists, Date() < deadline {
            if isOnScreen(skip, in: app) {
                tap(skip)
                // The introduction animates away after Skip. Give it time to leave, so the next tap does not
                // land on whatever Search draws in the same place.
                Thread.sleep(forTimeInterval: 2)
            } else {
                Thread.sleep(forTimeInterval: 0.25)
            }
        }
        XCTAssertTrue(search.waitForExistence(timeout: 5), "Search was not reached")
    }

    /// Taps the Settings tab once it is on screen, then waits for the screen. A cold CI simulator can
    /// take well over ten seconds to draw the first Settings frame (the test failed there, not in a bug,
    /// #502's run), so both waits are generous.
    private func openSettings(_ app: XCUIApplication) {
        let settings = app.element(labelled: "Settings")
        XCTAssertTrue(waitForOnScreen(settings, in: app, timeout: 45), "Settings tab was not ready")
        tap(settings)
        XCTAssertTrue(
            app.element(identifiedAs: "settings_version_row").waitForExistence(timeout: 45),
            "Settings did not open"
        )
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        return app
    }

    /// Polls until the element exists with a laid-out frame inside the window. Only `exists` and `frame`
    /// are read, because neither records a failure while the frame is still settling.
    private func waitForOnScreen(_ element: XCUIElement, in app: XCUIApplication, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !isOnScreen(element, in: app) {
            if Date() >= deadline { return false }
            Thread.sleep(forTimeInterval: 0.25)
        }
        return true
    }

    private func isOnScreen(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        guard element.exists else { return false }
        let frame = element.frame
        return frame.width > 0 && frame.height > 0 && app.frame.intersects(frame)
    }

    /// Like `isOnScreen`, but the element must lie fully above the bottom tab bar, and its frame must have
    /// stopped moving. A row scrolled under the bar, or still coasting after a swipe, is on screen but a tap
    /// at its centre misses it, so the share sheet never appears.
    private func waitForVisibleAboveBottomBar(_ element: XCUIElement, in app: XCUIApplication, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        var previous: CGRect?
        while true {
            if isVisibleAboveBottomBar(element, in: app) {
                let current = element.frame
                if current == previous { return true }
                previous = current
            } else {
                previous = nil
            }
            if Date() >= deadline { return false }
            Thread.sleep(forTimeInterval: 0.3)
        }
    }

    private func isVisibleAboveBottomBar(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        guard isOnScreen(element, in: app) else { return false }
        let frame = element.frame
        let settings = app.element(labelled: "Settings")
        // The Settings tab sits in the bottom bar; its top edge is where the bar starts. Without a laid-out
        // tab, the window bottom is the only limit.
        var bottom = app.frame.maxY
        if settings.exists, settings.frame.height > 0 { bottom = min(bottom, settings.frame.minY) }
        return frame.minY >= app.frame.minY && frame.maxY <= bottom
    }

    /// Taps by coordinate inside the element's frame. Unlike `tap()`, this does not ask for a hit point
    /// first, so a row that is on screen but not yet hit-testable does not fail the test.
    private func tap(_ element: XCUIElement) {
        element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
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
