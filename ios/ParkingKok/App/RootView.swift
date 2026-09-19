import SwiftUI

/// Root shell: one `NavigationStack` over the home screen.
///
/// Was the diagnostics readout alone while the engine was the whole product (P0). The
/// diagnostics screen has not moved out of the build — it now lives behind
/// settings → 개발자, because it is still the only readout for the field checklists in
/// `ios/README.md`.
struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase

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
        .preferredColorScheme(forcedColorScheme)
        // Composition happens here, not in `body`'s evaluation: docs/16 §5 keeps
        // storage work out of `body`, and opening a SwiftData container is storage work.
        .task {
            guard composition == nil else { return }
            // The shared one, not a fresh `live()`: a notification action answered from
            // the lock screen writes through the same composition, and two containers
            // would let the screen and the lock screen disagree about what is stored.
            let live = ParkingComposition.shared
            composition = live
            // DEV fixture first: it replaces the store, so refreshing before it runs
            // would cache rows it is about to delete.
            await live?.seedSampleDataIfRequested()
            if let live {
                // docs/04 §10 "periodic orphan cleanup". Once per launch, after the
                // store has been read — the model refuses to sweep on a failed read,
                // where an empty history would look like "delete every photo".
                live.model.refresh()
                await live.model.removeOrphanPhotos()
            }
            #if PK_DEV
                // The sweep above already refreshed; the detail route needs the seeded id.
                if let live, let route = ParkingSampleSeed.initialRoute(
                    activeParkingID: live.model.activeSession?.id
                ) {
                    path = [route]
                }
            #endif
        }
        // docs/04_IOS_IMPLEMENTATION.md §13: "App reconciles App Group revision to
        // in-memory UI on activation." A floor stepped on the widget while the app was
        // backgrounded is only recorded in the App Group projection until `refresh()`
        // adopts it, so this is what makes the home screen agree with the home screen.
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            composition?.model.refresh()
            // docs/05 §10 expiry is evaluated here rather than on a timer — see
            // `CandidateModel`. Activation is the moment it matters, because it is the
            // moment somebody could be shown a guess that is 46 minutes old.
            composition?.candidates.refresh()
        }
        // A notification tap can arrive before this stack exists, so the destination is
        // parked on the model and collected here rather than pushed from the responder.
        .onChange(of: composition?.candidates.pendingNavigation) { _, route in
            guard let route else { return }
            composition?.candidates.pendingNavigation = nil
            path.append(route)
        }
    }

    /// Capture hook, and `nil` in every shipped build.
    ///
    /// A real device cannot be switched to dark mode from the command line, and docs/10
    /// §11 asks for both appearances to be reviewed:
    ///
    /// ```sh
    ///   --environment-variables '{"PK_FORCE_COLOR_SCHEME":"dark"}'
    /// ```
    private var forcedColorScheme: ColorScheme? {
        #if PK_DEV
            switch ProcessInfo.processInfo.environment["PK_FORCE_COLOR_SCHEME"] {
            case "dark": return .dark
            case "light": return .light
            default: return nil
            }
        #else
            return nil
        #endif
    }

    @ViewBuilder
    private var content: some View {
        if let composition {
            HomeView(
                model: composition.model,
                candidates: composition.candidates,
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
        case .notificationHistory:
            if let composition {
                NotificationHistoryView(candidates: composition.candidates, path: $path)
            }
        case let .candidateConfirmation(id):
            if let composition, let candidate = composition.candidates.candidate(id: id) {
                CandidateConfirmationView(
                    candidate: candidate,
                    candidates: composition.candidates,
                    parking: composition.model,
                    path: $path
                )
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
