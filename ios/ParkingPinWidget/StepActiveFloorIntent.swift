import AppIntents
import WidgetKit

/// The widget's `−` / `+` key (docs/06 §7a "Rapid taps resolve by delta, not by value").
///
/// The intent carries a **delta** and the session the key was drawn for — never a
/// resulting floor. Two taps landing together therefore move two floors; had the callback
/// carried a value, the second write would have silently discarded the first.
///
/// It holds no logic of its own. docs/06 §7 — written for Glance, but the same
/// instruction — says the callback delegates to the repository layer and that product
/// state must not live inside widget state. The read-modify-write is
/// `ActiveParkingSnapshotStoring.step`, which both processes share.
struct StepActiveFloorIntent: AppIntent {
    static let title: LocalizedStringResource = "주차 층 변경"
    static let description = IntentDescription("위젯에서 현재 주차 층을 한 층 위아래로 바꿉니다.")
    /// The whole point is changing the floor in place; opening the app would be a
    /// different feature.
    static let openAppWhenRun = false

    @Parameter(title: "변경량")
    var delta: Int

    /// The parking the widget had on screen when this key was drawn. Echoed back so a tap
    /// that lands after the session ended can be dropped instead of being applied to
    /// whatever session came next.
    @Parameter(title: "주차 ID")
    var sessionId: String

    init() {}

    init(delta: Int, sessionId: UUID) {
        self.delta = delta
        self.sessionId = sessionId.uuidString
    }

    func perform() async throws -> some IntentResult {
        // Reload whatever happened. A delta the domain rejects is a no-op that still
        // reloads, "so the display snaps back to the true value rather than appearing to
        // have moved" (docs/06 §7a); a dropped tap must stop showing a parking that is
        // already over.
        defer { WidgetCenter.shared.reloadTimelines(ofKind: ActiveParkingSnapshot.widgetKind) }

        // docs/06 §7a: the one place entitlement is read. A PROD build renders the keys
        // disabled, and this refuses the mutation even if one is somehow delivered.
        guard WidgetStepEntitlement.isEnabled else { return .result() }
        guard let sessionId = UUID(uuidString: sessionId),
              let store = FileActiveParkingSnapshotStore.appGroup()
        else {
            return .result()
        }

        switch store.step(by: delta, expecting: sessionId, at: .now) {
        case .stepped:
            break
        case .rejected:
            AppLog.lifecycle.info("widget floor step rejected by the floor domain")
        case .dropped:
            // The interesting one: the parking ended between render and tap.
            AppLog.lifecycle.info("widget floor step dropped: session no longer active")
        case .failed:
            AppLog.lifecycle.error("widget floor step could not be written")
        }
        return .result()
    }
}
