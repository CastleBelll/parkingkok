import SwiftUI

/// Root shell: one `NavigationStack` over the home screen.
///
/// Was the diagnostics readout alone while the engine was the whole product (P0). The
/// diagnostics screen has not moved out of the build — it now lives behind
/// settings → 개발자, because it is still the only readout for the field checklists in
/// `ios/README.md`.
struct RootView: View {
    private let appInfo: AppInfo
    @State private var composition: ParkingComposition?
    @State private var path: [AppRoute] = []

    init(appInfo: AppInfo = .current) {
        self.appInfo = appInfo
    }

    var body: some View {
        NavigationStack(path: $path) {
            content
                .navigationDestination(for: AppRoute.self) { route in
                    destination(for: route)
                }
        }
        .tint(PKColor.primary)
        // Composition happens here, not in `body`'s evaluation: docs/16 §5 keeps
        // storage work out of `body`, and opening a SwiftData container is storage work.
        .task {
            guard composition == nil else { return }
            let live = ParkingComposition.live()
            composition = live
            #if PK_DEV
                // The model has not been asked for anything yet; the detail route
                // needs the seeded id.
                live?.model.refresh()
                if let live, let route = ParkingSampleSeed.initialRoute(
                    activeParkingID: live.model.activeSession?.id
                ) {
                    path = [route]
                }
            #endif
        }
    }

    @ViewBuilder
    private var content: some View {
        if let composition {
            HomeView(
                model: composition.model,
                storageWarning: composition.storageWarning,
                path: $path
            )
        } else {
            LoadingOrFailureView()
        }
    }

    @ViewBuilder
    private func destination(for route: AppRoute) -> some View {
        switch route {
        case let .parkingDetail(id):
            if let composition {
                ParkingDetailView(model: composition.model, sessionID: id)
            }
        case .history:
            if let composition {
                HistoryView(model: composition.model, path: $path)
            }
        case .settings:
            SettingsView(appInfo: appInfo, model: composition?.model, path: $path)
        case .diagnostics:
            DiagnosticsView(appInfo: appInfo)
        }
    }
}

/// Shown for the instant before the store opens, and permanently if it never does.
///
/// Not a spinner over the whole app: docs/10 §13 says local parking renders immediately
/// and nothing global may block home. This appears only when there is genuinely nothing
/// to render yet.
private struct LoadingOrFailureView: View {
    @State private var hasSettled = false

    var body: some View {
        VStack(spacing: PKSpacing.m) {
            if hasSettled {
                Image(systemName: "externaldrive.badge.exclamationmark")
                    .font(.largeTitle)
                    .foregroundStyle(PKColor.danger)
                Text("주차 기록 저장소를 열지 못했어요.")
                    .font(PKTypography.row)
                    .foregroundStyle(PKColor.textPrimary)
                Text("앱을 다시 실행해 주세요. 기기 저장 공간이 부족한지 확인해 주세요.")
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
                    .multilineTextAlignment(.center)
            } else {
                ProgressView()
            }
        }
        .padding(PKSpacing.xl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PKColor.background)
        .task {
            // One run loop is enough for a local container; anything longer is a failure
            // worth naming rather than an indefinite spinner.
            try? await Task.sleep(for: .milliseconds(600))
            hasSettled = true
        }
    }
}

#Preview {
    RootView()
}
