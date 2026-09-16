import SwiftUI

/// Root shell. P0 has exactly one screen — the detection diagnostics readout — because
/// CLAUDE.md's development order says the product UI comes after the engine.
struct RootView: View {
    private let appInfo: AppInfo

    init(appInfo: AppInfo = .current) {
        self.appInfo = appInfo
    }

    var body: some View {
        NavigationStack {
            DiagnosticsView(appInfo: appInfo)
        }
    }
}

#Preview {
    RootView()
}
