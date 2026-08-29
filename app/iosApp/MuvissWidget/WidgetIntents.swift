import AppIntents
import WidgetKit
import Shared

/// Where the "just ticked, offer Undo" marker lives on this side.
///
/// The Android widget keeps this in Glance's own per-widget state; WidgetKit
/// has no equivalent, so it goes in the App Group's shared defaults — the
/// same container the database lives in (ADR 0014). One marker for the whole
/// widget, matching Android's one-per-instance closely enough: a person can
/// only have just-ticked one thing.
enum WidgetUndoStore {

    private static let key = "justTickedEpisodeId"

    private static var defaults: UserDefaults? {
        UserDefaults(suiteName: "group.com.codingpit.muviss")
    }

    static func justTicked() -> String? {
        defaults?.string(forKey: key)
    }

    static func set(_ episodeId: String) {
        defaults?.set(episodeId, forKey: key)
    }

    static func clear() {
        defaults?.removeObject(forKey: key)
    }
}

/// Ticks a row's episode straight from the home screen.
///
/// `perform()` runs in *this* extension's process, not the app's — which is
/// the whole reason the database had to move into an App Group. The write
/// goes through the same `ProgressApi` the app uses, so the tick and its
/// `episodePlay` row land in one transaction (ADR 0011).
///
/// The Undo marker is set after the write, mirroring the Android
/// `TickAction`: the write itself reloads the timelines (via the app's
/// `WidgetRefresher` seam) and that reload clears the marker, so setting it
/// first would lose it.
struct TickEpisodeIntent: AppIntent {

    static var title: LocalizedStringResource = "Mark episode seen"

    @Parameter(title: "Episode")
    var episodeId: String

    init() {}

    init(episodeId: String) {
        self.episodeId = episodeId
    }

    func perform() async throws -> some IntentResult {
        IosWidgetBridge.shared.tick(episodeId: episodeId)
        WidgetUndoStore.set(episodeId)
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

/// Takes a widget tick back, both halves of it — the same call the in-app
/// undo snackbar makes. Safe here because the widget can only ever tick an
/// episode that was next *unseen*, so there is no earlier viewing to lose.
struct UndoTickIntent: AppIntent {

    static var title: LocalizedStringResource = "Undo marking episode seen"

    @Parameter(title: "Episode")
    var episodeId: String

    init() {}

    init(episodeId: String) {
        self.episodeId = episodeId
    }

    func perform() async throws -> some IntentResult {
        IosWidgetBridge.shared.undoTick(episodeId: episodeId)
        WidgetUndoStore.clear()
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}
