import SwiftUI

/// The app's root screen (`design-references/01-home-main.png`).
///
/// Hierarchy is docs/10 §6, in order: current floor, zone/spot, elapsed, `-`/`+`,
/// location, end parking, history preview. The floor is the largest thing on the screen
/// because looking it up is the entire product promise (docs/01 §2).
///
/// Nothing here reaches the network, and nothing blocks on anything (docs/01 §8
/// "Network must not block home"): every value comes from the local store.
struct HomeView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @Bindable private var model: ParkingModel
    private let storageWarning: String?
    @Binding private var path: [AppRoute]

    @State private var isManualSheetPresented = false
    /// Ticks once a minute so the elapsed line ages while the screen is open, without a
    /// timer that survives the screen.
    @State private var displayNow = Date()

    init(model: ParkingModel, storageWarning: String?, path: Binding<[AppRoute]>) {
        self.model = model
        self.storageWarning = storageWarning
        _path = path
    }

    var body: some View {
        PKScreen {
            PKBrandHeader(
                onOpenSettings: { path.append(.settings(focus: nil)) },
                onOpenNotificationSettings: { path.append(.settings(focus: .notifications)) }
            )
            .pkEntrance(0)

            if let storageWarning {
                PKNoticeCard(text: storageWarning)
                    .pkEntrance(1)
            }
            if let failure = model.failure {
                PKNoticeCard(text: failure)
                    .pkEntrance(1)
            }

            // The active card and the empty card are the same slot, so parking starting
            // or ending is a cross-fade in place rather than one card popping out and
            // another popping in. `pkWithAnimation` at the call sites drives it.
            Group {
                if let active = model.activeSession {
                    ActiveParkingCard(
                        session: active,
                        now: displayNow,
                        onStepFloor: { model.stepActiveFloor(by: $0) },
                        onEditFloor: { isManualSheetPresented = true }
                    )
                    .pkEntrance(1)
                    HomeActionRow(
                        icon: "mappin.and.ellipse",
                        tint: .accent,
                        title: "주차 위치 보기",
                        subtitle: "저장된 주차 정보를 확인하세요"
                    ) {
                        path.append(.parkingDetail(id: active.id))
                    }
                    .pkEntrance(2)
                    PKPrimaryActionButton(
                        title: "주차 종료",
                        subtitle: "주차를 종료하고 기록을 저장합니다",
                        systemImage: "flag.checkered"
                    ) {
                        pkWithAnimation(PKMotion.sessionChange, reduceMotion: reduceMotion) {
                            _ = model.endActiveParking()
                        }
                    }
                    .pkEntrance(3)
                } else {
                    EmptyParkingCard { isManualSheetPresented = true }
                        .pkEntrance(1)
                }
            }
            .transition(.opacity)

            RecentParkingSection(
                sessions: model.homePreviewSessions,
                onSelect: { path.append(.parkingDetail(id: $0.id)) },
                onSeeAll: { path.append(.history) }
            )
            .pkEntrance(4)

            PKBrandFooter()
                .pkEntrance(5)
        }
        .navigationBarHidden(true)
        // `.task`/`.onAppear`, never `body`: docs/16 §5 keeps storage work out of render.
        .onAppear {
            model.refresh()
            displayNow = model.now
        }
        .sheet(isPresented: $isManualSheetPresented) {
            ManualParkingSheet(model: model, editing: model.activeSession)
        }
        .task {
            // One minute is the smallest unit the elapsed line shows, so anything finer
            // would redraw for nothing.
            for await _ in ClockTicker.minutes() {
                displayNow = model.now
            }
        }
    }
}

/// docs/02 §8's empty home.
private struct EmptyParkingCard: View {
    let onSaveManually: () -> Void

    var body: some View {
        PKCard {
            VStack(alignment: .leading, spacing: PKSpacing.l) {
                Text("현재 저장된 주차 위치가 없어요.")
                    .font(PKTypography.sectionTitle)
                    .foregroundStyle(PKColor.textPrimary)
                Text("주차하면 주차콕이 알려드릴게요.\n지금 바로 직접 저장할 수도 있어요.")
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
                Button("직접 저장", action: onSaveManually)
                    .buttonStyle(PKPrimaryButtonStyle())
            }
            .padding(PKSpacing.xl)
        }
    }
}

/// One of the tappable rows under the parking card.
private struct HomeActionRow: View {
    let icon: String
    let tint: PKIconChip.Tint
    let title: String
    let subtitle: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: PKSpacing.l) {
                PKIconChip(icon, tint: tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(PKTypography.row)
                        .foregroundStyle(PKColor.textPrimary)
                    Text(subtitle)
                        .font(PKTypography.supporting)
                        .foregroundStyle(PKColor.textSecondary)
                }
                Spacer(minLength: PKSpacing.s)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(PKColor.textSecondary)
                    .accessibilityHidden(true)
            }
            .padding(PKSpacing.l)
            .frame(minHeight: PKSize.minimumTouchTarget)
        }
        // Surface, border, lift and press all come from the style, so a tappable card
        // cannot drift away from a card that merely sits there.
        .buttonStyle(PKSurfaceButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// Home's history preview — deliberately three rows (docs/19: "최근 기록은 홈 하단
/// preview 성격으로 제한", and history must not outweigh the active parking).
private struct RecentParkingSection: View {
    let sessions: [ParkingSession]
    let onSelect: (ParkingSession) -> Void
    let onSeeAll: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: PKSpacing.m) {
            HStack {
                Text("최근 주차")
                    .font(PKTypography.sectionTitle)
                    .foregroundStyle(PKColor.textPrimary)
                Spacer()
                Button(action: onSeeAll) {
                    HStack(spacing: PKSpacing.xs) {
                        Text("전체보기")
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                    }
                    .font(PKTypography.caption)
                }
                .tint(PKColor.textSecondary)
            }

            if sessions.isEmpty {
                Text("아직 저장된 기록이 없어요.")
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
                    .padding(.vertical, PKSpacing.s)
            } else {
                VStack(spacing: PKSpacing.s) {
                    ForEach(sessions) { session in
                        Button { onSelect(session) } label: {
                            ParkingHistoryRow(session: session, style: .compact)
                        }
                        .buttonStyle(PKSurfaceButtonStyle(radius: PKRadius.row))
                    }
                }
            }
        }
    }
}

/// A minute-resolution ticker for elapsed labels.
///
/// An `AsyncStream` rather than a `Timer`: it is owned by the `.task` that consumes it,
/// so it is cancelled with the screen (docs/16 §6 — every long-lived listener has an
/// owner).
enum ClockTicker {
    static func minutes() -> AsyncStream<Void> {
        AsyncStream { continuation in
            let task = Task {
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(60))
                    guard !Task.isCancelled else { break }
                    continuation.yield(())
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
