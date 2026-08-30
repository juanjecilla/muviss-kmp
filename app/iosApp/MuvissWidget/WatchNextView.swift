import SwiftUI
import WidgetKit
import Shared

/// The same amber as the app. These are `MuvissPalette`'s literals
/// (core/designsystem's Color.kt), duplicated here because a widget extension
/// renders SwiftUI and cannot read a Compose `ColorScheme`. `MuvissPaletteTest`
/// is what keeps the Kotlin side from drifting; if these ever need to change,
/// they change in both places.
enum MuvissWidgetColors {
    static let background = Color(red: 0x14 / 255, green: 0x12 / 255, blue: 0x0E / 255)
    static let surface = Color(red: 0x21 / 255, green: 0x1E / 255, blue: 0x18 / 255)
    static let onSurface = Color(red: 0xE9 / 255, green: 0xE2 / 255, blue: 0xD4 / 255)
    static let onSurfaceVariant = Color(red: 0xCF / 255, green: 0xC6 / 255, blue: 0xB4 / 255)
    static let primary = Color(red: 0xFF / 255, green: 0xCB / 255, blue: 0x6B / 255)
    static let onPrimary = Color(red: 0x43 / 255, green: 0x2C / 255, blue: 0x00 / 255)
}

struct WatchNextView: View {
    let entry: WatchNextEntry

    var body: some View {
        if entry.rows.isEmpty {
            EmptyStateView(
                title: "Nothing in progress",
                message: "Start watching something and it will show up here."
            )
        } else if entry.rows.allSatisfy({ $0.episodeId == nil }) && entry.justTicked == nil {
            // The upgrade case ADR 0015 accepts: titles are in progress but
            // the catalog table is still empty, so no episode can be named.
            EmptyStateView(
                title: "Open Muviss once",
                message: "Episode details are downloaded the first time you open the app."
            )
        } else {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(entry.rows, id: \.mediaId) { row in
                    WatchNextRowView(row: row, justTicked: entry.justTicked)
                }
            }
        }
    }
}

/// `message` rather than `body`: a stored `body` property would collide with
/// SwiftUI's own `var body: some View`.
private struct EmptyStateView: View {
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 4) {
            Text(title)
                .font(.headline)
                .foregroundStyle(MuvissWidgetColors.onSurface)
            Text(message)
                .font(.caption)
                .multilineTextAlignment(.center)
                .foregroundStyle(MuvissWidgetColors.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct WatchNextRowView: View {
    let row: IosWidgetRow
    let justTicked: String?

    /// Matched on the show rather than the episode: by the time the widget
    /// redraws, the ticked episode is no longer this row's next one.
    private var isJustTicked: Bool {
        guard let justTicked else { return false }
        return justTicked.hasPrefix(row.mediaId + "/")
    }

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                    .foregroundStyle(MuvissWidgetColors.onSurface)
                Text(subtitle)
                    .font(.caption)
                    .lineLimit(1)
                    .foregroundStyle(MuvissWidgetColors.onSurfaceVariant)
            }
            Spacer(minLength: 4)
            action
        }
        .padding(10)
        .background(MuvissWidgetColors.surface, in: RoundedRectangle(cornerRadius: 12))
        .widgetURL(URL(string: "muviss://title/\(row.mediaId)"))
    }

    private var subtitle: String {
        if isJustTicked { return "Marked seen" }
        guard let label = row.episodeLabel else { return "Open to load episodes" }
        guard let name = row.episodeName else { return label }
        return "\(label) · \(name)"
    }

    @ViewBuilder
    private var action: some View {
        if isJustTicked, let ticked = justTicked {
            Button(intent: UndoTickIntent(episodeId: ticked)) { pill("Undo") }
                .buttonStyle(.plain)
        } else if let episodeId = row.episodeId {
            Button(intent: TickEpisodeIntent(episodeId: episodeId)) { pill("Seen") }
                .buttonStyle(.plain)
        }
    }

    private func pill(_ label: String) -> some View {
        Text(label)
            .font(.caption.weight(.medium))
            .foregroundStyle(MuvissWidgetColors.onPrimary)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(MuvissWidgetColors.primary, in: Capsule())
    }
}
