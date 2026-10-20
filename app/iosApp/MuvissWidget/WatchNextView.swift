import SwiftUI
import UIKit
import WidgetKit
import Shared

/// The widget's colours, read from `MuvissPalette` through `IosWidgetPalette`
/// (app/shared), so this file carries no literals and cannot drift from the
/// app or from the Android widget. Each colour follows the system appearance:
/// the dynamic `UIColor` asks the trait collection WidgetKit hands it, which is
/// how a widget follows light and dark mode.
enum MuvissWidgetColors {
    static let background = role(IosWidgetPalette.shared.BACKGROUND)
    static let surface = role(IosWidgetPalette.shared.SURFACE)
    static let onSurface = role(IosWidgetPalette.shared.ON_SURFACE)
    static let onSurfaceVariant = role(IosWidgetPalette.shared.ON_SURFACE_VARIANT)
    static let primary = role(IosWidgetPalette.shared.PRIMARY)
    static let onPrimary = role(IosWidgetPalette.shared.ON_PRIMARY)

    private static func role(_ name: String) -> Color {
        Color(uiColor: UIColor { traits in
            let argb = UInt32(bitPattern: IosWidgetBridge.shared.colorArgb(
                role: name,
                dark: traits.userInterfaceStyle == .dark
            ))
            return UIColor(
                red: CGFloat((argb >> 16) & 0xFF) / 255,
                green: CGFloat((argb >> 8) & 0xFF) / 255,
                blue: CGFloat(argb & 0xFF) / 255,
                alpha: CGFloat((argb >> 24) & 0xFF) / 255
            )
        })
    }
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
    let title: LocalizedStringKey
    let message: LocalizedStringKey

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
        if isJustTicked { return String(localized: "Marked seen") }
        guard let label = row.episodeLabel else { return String(localized: "Open to load episodes") }
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

    private func pill(_ label: LocalizedStringKey) -> some View {
        Text(label)
            .font(.caption.weight(.medium))
            .foregroundStyle(MuvissWidgetColors.onPrimary)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(MuvissWidgetColors.primary, in: Capsule())
    }
}
