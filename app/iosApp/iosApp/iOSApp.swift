import SwiftUI
import Shared

@main
struct iOSApp: App {
    // Guarantees AppDelegate.application(_:didFinishLaunchingWithOptions:)
    // runs at process launch even when this struct's body/WindowGroup is
    // never composed (a background BGAppRefreshTask launch) — see
    // AppDelegate's doc comment.
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                // Every `muviss://` URL enters here: the widget's
                // `.widgetURL` (muviss://title/<id>) and the OAuth redirect
                // (muviss://auth-callback?code=…, ADR 0014). Parsing is
                // Kotlin's — IosDeepLinks.handle — so this stays a single
                // string hand-off and the routes live next to their
                // consumers in MuvissApp. The scheme itself is registered in
                // Info.plist's CFBundleURLTypes.
                //
                // onOpenURL, not application(_:open:options:): SwiftUI
                // delivers a cold-launch URL here too, and the redirect
                // routinely arrives on a cold start.
                .onOpenURL { url in
                    _ = IosDeepLinks.shared.handle(url: url.absoluteString)
                }
        }
    }
}
