import AppIntents

/// `내 차 어디?` — Siri and the Shortcuts app (docs/02 §17, DECIDED 2026-10-01).
///
/// Answered in place: the reply is a sentence, not a screen, so the app does not come
/// forward. It reads the widget's App Group projection, which the app keeps current for the
/// active parking.
struct WhereIsMyCarIntent: AppIntent {
    static let title: LocalizedStringResource = "내 차 위치"
    static let description = IntentDescription("진행 중인 주차의 층과 구역, 주차한 지 얼마나 됐는지 알려줘요.")
    static let openAppWhenRun = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let snapshot = FileActiveParkingSnapshotStore.appGroup()?.read()
        return .result(dialog: IntentDialog(stringLiteral: WhereIsMyCarAnswer.text(for: snapshot, now: .now)))
    }
}

/// The phrases Siri listens for. Each must name the app (`\(.applicationName)`), which is
/// what keeps them from colliding with every other app's "where is my car".
struct ParkingPinShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: WhereIsMyCarIntent(),
            phrases: [
                "\(.applicationName)에서 내 차 위치",
                "\(.applicationName) 내 차 어디",
                "\(.applicationName) 주차 위치"
            ],
            shortTitle: "내 차 위치",
            systemImageName: "car.fill"
        )
    }
}
