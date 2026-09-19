import SwiftUI
import WidgetKit

/// One rendering of the active parking. `date` is what the elapsed line is measured to,
/// which is why the timeline is a series of them rather than a single entry.
struct ActiveParkingEntry: TimelineEntry, Sendable {
    let date: Date
    /// `nil` when there is no active parking — the widget's empty state.
    let snapshot: ActiveParkingSnapshot?
    /// Read once, here, from `WidgetStepEntitlement` (docs/06 §7a).
    let isSteppingEntitled: Bool
}

/// Reads the App Group projection and nothing else (docs/06 §3).
///
/// There is no configuration, so `StaticConfiguration` and a plain `TimelineProvider`:
/// the widget shows the one active parking or says there is none.
struct ActiveParkingTimelineProvider: TimelineProvider {
    /// The elapsed line is minute-granular (`ParkingElapsed`), so an entry a minute is
    /// exactly enough — anything finer redraws for no visible change.
    private static let step: TimeInterval = 60
    /// An hour of entries ahead. WidgetKit budgets reloads, and a parking that outlives
    /// the timeline gets a fresh one when the last entry is reached.
    private static let entryCount = 60

    private let store: (any ActiveParkingSnapshotStoring)?

    init(store: (any ActiveParkingSnapshotStoring)? = FileActiveParkingSnapshotStore.appGroup()) {
        self.store = store
    }

    /// The gallery placeholder. Never the user's data — it is drawn before the widget is
    /// added, for anyone scrolling the picker.
    func placeholder(in context: Context) -> ActiveParkingEntry {
        ActiveParkingEntry(date: .now, snapshot: .placeholder, isSteppingEntitled: true)
    }

    func getSnapshot(in context: Context, completion: @escaping (ActiveParkingEntry) -> Void) {
        // The widget gallery previews with `isPreview`, where reading the real container
        // would show an empty card to anyone who has not parked yet.
        guard !context.isPreview else {
            completion(placeholder(in: context))
            return
        }
        completion(entry(at: .now))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ActiveParkingEntry>) -> Void) {
        let now = Date.now
        let snapshot = store?.read()
        let isEntitled = WidgetStepEntitlement.isEnabled
        let entries = (0 ..< Self.entryCount).map { index in
            ActiveParkingEntry(
                date: now.addingTimeInterval(Double(index) * Self.step),
                snapshot: snapshot,
                isSteppingEntitled: isEntitled
            )
        }
        // `.atEnd`: the projection changes when the app or an intent says so, and both
        // reload explicitly. Polling more often would spend the reload budget on a file
        // that has not moved.
        completion(Timeline(entries: entries, policy: .atEnd))
    }

    private func entry(at date: Date) -> ActiveParkingEntry {
        ActiveParkingEntry(
            date: date,
            snapshot: store?.read(),
            isSteppingEntitled: WidgetStepEntitlement.isEnabled
        )
    }
}

/// docs/06 §7a: `systemSmall` and `systemMedium`. No Lock Screen or StandBy family in v1.
struct ActiveParkingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(
            kind: ActiveParkingSnapshot.widgetKind,
            provider: ActiveParkingTimelineProvider()
        ) { entry in
            ActiveParkingWidgetView(entry: entry)
                .containerBackground(PKColor.surface, for: .widget)
        }
        // The brand is 주차핀. Only the gallery sees this string; the tile itself
        // shows no brand mark, because nothing may compete with the floor.
        .configurationDisplayName("주차핀")
        .description("주차한 층과 경과 시간을 홈 화면에서 바로 확인해요.")
        .supportedFamilies([.systemSmall, .systemMedium])
        // The tile draws its own padding, and the floor needs every point of the face.
        .contentMarginsDisabled()
    }
}

extension ActiveParkingSnapshot {
    /// Gallery-only fixture. A plausible parking so the picker shows the widget doing its
    /// job — never a real coordinate, never a real record (docs/09).
    static let placeholder = ActiveParkingSnapshot(
        sessionId: UUID(),
        revision: 1,
        updatedAt: .now,
        startedAt: .now.addingTimeInterval(-5040),
        floor: FloorValue.parse("B3"),
        zone: "A구역",
        spot: "142"
    )
}
