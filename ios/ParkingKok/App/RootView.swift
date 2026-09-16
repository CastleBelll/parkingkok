import SwiftUI

/// Placeholder root screen. Real navigation arrives with the detection engine
/// milestones; see docs/04_IOS_IMPLEMENTATION.md for the P0 ordering.
struct RootView: View {
    private let appInfo: AppInfo

    init(appInfo: AppInfo = .current) {
        self.appInfo = appInfo
    }

    var body: some View {
        VStack(spacing: 8) {
            Text(appInfo.displayName)
                .font(.largeTitle.weight(.semibold))
            Text(appInfo.versionSummary)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}

#Preview {
    RootView()
}
