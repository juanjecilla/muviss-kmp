import SwiftUI

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
        }
    }
}