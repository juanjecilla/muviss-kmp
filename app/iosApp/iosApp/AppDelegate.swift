import UIKit
import Shared

/// Deliberately thin (EPIC 11 / issue #13): the only reason this exists at
/// all is that `application(_:didFinishLaunchingWithOptions:)` is the one
/// hook Apple guarantees runs before launch completes regardless of *why*
/// the process was launched — a user tap, a `BGAppRefreshTask`, or a
/// notification tap — which a SwiftUI `App`'s `WindowGroup`/scene body is
/// not (a background launch may never compose it). Every real decision
/// (starting Koin, Sentry, registering the background task, requesting
/// notification permission) lives in Kotlin's `IosAppStartup.start()`
/// (app/shared's iosMain) so it's covered by the same shared-code tests and
/// review as the rest of the app — see that file's doc comment.
class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        IosAppStartup.shared.start()
        return true
    }
}
