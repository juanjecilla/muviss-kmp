import SwiftUI
import WidgetKit
import Shared

/// The "Watch next" home-screen widget (EPIC 22).
///
/// A widget extension is a separate process from the app, so nothing here can
/// assume `AppDelegate` has run: `IosWidgetBridge.ensureStarted()` brings up
/// its own Koin graph over the App Group database the app writes to (ADR
/// 0014). Without that shared container this process would open its own empty
/// SQLite file and the widget would show an empty library forever.
///
/// The view is hand-written SwiftUI rather than shared Compose because
/// WidgetKit renders static SwiftUI views through its own archiving pipeline —
/// a `ComposeUIViewController` cannot be drawn in a widget at all. The
/// colours below are the same hexes as `MuvissPalette`, so the two platforms'
/// widgets match each other and both match the app.

struct WatchNextEntry: TimelineEntry {
    let date: Date
    let rows: [IosWidgetRow]
    /// The episode just ticked from this widget; its row offers Undo instead of a tick.
    let justTicked: String?
}

struct WatchNextProvider: TimelineProvider {

    func placeholder(in context: Context) -> WatchNextEntry {
        WatchNextEntry(date: Date(), rows: [], justTicked: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (WatchNextEntry) -> Void) {
        completion(entry(for: context))
    }

    /// One entry, reloaded at the next local midnight.
    ///
    /// "Next unseen *aired* episode" changes answer at the day boundary with
    /// nothing having been written, so the timeline has to be rebuilt then
    /// even if the person never touches anything. Every other reload is
    /// pushed by a write (`IosWidgetRefresher`), which is why there is no
    /// polling interval here — the Android side arms the same boundary with a
    /// one-shot WorkManager job.
    func getTimeline(in context: Context, completion: @escaping (Timeline<WatchNextEntry>) -> Void) {
        let policy = Calendar.current.nextDate(
            after: Date(),
            matching: DateComponents(hour: 0, minute: 0),
            matchingPolicy: .nextTime
        )
        completion(
            Timeline(
                entries: [entry(for: context)],
                policy: policy.map { .after($0) } ?? .atEnd
            )
        )
    }

    private func entry(for context: Context) -> WatchNextEntry {
        IosWidgetBridge.shared.ensureStarted()
        let limit = rowLimit(for: context.family)
        let rows = IosWidgetBridge.shared.watchNextRows(limit: Int32(limit))
        return WatchNextEntry(date: Date(), rows: rows, justTicked: WidgetUndoStore.justTicked())
    }

    /// Mirrors Android's `WidgetSize`: one, three or five rows.
    private func rowLimit(for family: WidgetFamily) -> Int {
        switch family {
        case .systemSmall: return 1
        case .systemMedium: return 3
        default: return 5
        }
    }
}

struct MuvissWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "MuvissWatchNext", provider: WatchNextProvider()) { entry in
            WatchNextView(entry: entry)
                .containerBackground(MuvissWidgetColors.background, for: .widget)
        }
        .configurationDisplayName("Watch next")
        .description("The shows you are part-way through, with a one-tap tick.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@main
struct MuvissWidgetBundle: WidgetBundle {
    var body: some Widget {
        MuvissWidget()
    }
}
