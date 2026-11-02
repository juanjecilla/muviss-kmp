import AppIntents
import WidgetKit
import Shared

/// The "just ticked, offer Undo" marker, as the widget sees it.
///
/// The marker itself lives in Kotlin (`WidgetUndoMarker` in app/shared), in the
/// App Group's defaults, so the app's refresh and this extension read and clear
/// one value. Android keeps the same marker in Glance's per-widget state. One
/// marker for the whole widget, matching Android's one-per-instance closely
/// enough: a person can only have just-ticked one thing.
enum WidgetUndoStore {

    static func justTicked() -> String? {
        IosWidgetBridge.shared.justTicked()
    }

    static func set(_ episodeId: String) {
        IosWidgetBridge.shared.markJustTicked(episodeId: episodeId)
    }

    static func clear() {
        IosWidgetBridge.shared.clearJustTicked()
    }
}

/// Ticks a row's episode straight from the home screen.
///
/// `perform()` runs in *this* extension's process, not the app's — which is
/// the whole reason the database had to move into an App Group. The write
/// goes through the same `ProgressApi` the app uses, so the tick and its
/// `episodePlay` row land in one transaction (ADR 0011).
///
/// The Undo marker is set after the write, mirroring the Android `TickAction`.
/// The write runs in this extension, where no `WidgetRefresher` is installed,
/// so nothing here clears the marker; it has to be in place before the reload
/// below redraws the row, which is why it is set before `reloadAllTimelines()`.
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
