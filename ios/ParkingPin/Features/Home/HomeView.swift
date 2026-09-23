import PhotosUI
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
    @Bindable private var candidates: CandidateModel
    private let storageWarning: String?
    @Binding private var path: [AppRoute]

    @State private var isManualSheetPresented = false
    // docs/02 §6a, reachable by hand. The pillar reader was wired to the confirmation
    // screen and nowhere else, so anyone who had never had a drive detected — everyone, at
    // first — typed the floor and attached a photo afterwards, by which time the read could
    // not help. The camera opens *before* the save from here.
    @State private var isChoosingPillarSource = false
    @State private var isCapturingPillar = false
    @State private var isPickingPillarFromLibrary = false
    @State private var pillarLibraryItem: PhotosPickerItem?
    @State private var pillarPhotoData: Data?
    @State private var pillarReading: PillarReading?
    @State private var isReadingPillar = false
    private let pillarReader: any PillarTextReading = VisionPillarTextReader()
    /// Ticks once a minute so the elapsed line ages while the screen is open, without a
    /// timer that survives the screen.
    @State private var displayNow = Date()

    init(
        model: ParkingModel,
        candidates: CandidateModel,
        storageWarning: String?,
        path: Binding<[AppRoute]>
    ) {
        self.model = model
        self.candidates = candidates
        self.storageWarning = storageWarning
        _path = path
    }

    var body: some View {
        PKScreen {
            PKBrandHeader(
                // docs/10 §7b: the dot is the whole reason the screen exists — a
                // notification swiped away in the car is otherwise lost until it expires.
                hasUnreadNotification: candidates.hasUnansweredCandidate,
                onOpenSettings: { path.append(.settings) },
                onOpenNotifications: { path.append(.notificationHistory) }
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
                } else if candidates.pending == nil {
                    EmptyParkingCard(
                        onSaveManually: { isManualSheetPresented = true },
                        onPhotoEntry: beginPillarEntry
                    )
                        .pkEntrance(1)
                }

                // docs/05 §10a: "Denied, the candidate is saved and surfaces in the app on
                // next launch." This row is that surface, and it is what makes the
                // notification an optimisation rather than the feature. It sits *under*
                // the active parking because docs/10 §6 ranks the current location first —
                // a guess about a new one must never outrank the one being looked up.
                if let candidate = candidates.pending {
                    PendingCandidateCard(candidate: candidate) {
                        path.append(.candidateConfirmation(id: candidate.id))
                    }
                    .pkEntrance(model.activeSession == nil ? 1 : 4)
                }
            }
            .transition(.opacity)

            RecentParkingSection(
                sessions: model.homePreviewSessions,
                onSelect: { path.append(.parkingDetail(id: $0.id)) },
                onSeeAll: { path.append(.history) }
            )
            .pkEntrance(4)
            .pkEntrance(5)
        }
        .navigationBarHidden(true)
        // `.task`/`.onAppear`, never `body`: docs/16 §5 keeps storage work out of render.
        .onAppear {
            model.refresh()
            candidates.refresh()
            displayNow = model.now
        }
        .sheet(isPresented: $isManualSheetPresented, onDismiss: clearPillarEntry) {
            ManualParkingSheet(
                model: model,
                editing: model.activeSession,
                suggestion: pillarReading,
                pillarPhoto: pillarPhotoData
            )
        }
        .confirmationDialog(
            "사진으로 입력",
            isPresented: $isChoosingPillarSource,
            titleVisibility: .visible
        ) {
            ForEach(ParkingPhotoSource.available) { source in
                Button(source.title) { presentPillarSource(source) }
            }
            Button("취소", role: .cancel) {}
        }
        .fullScreenCover(isPresented: $isCapturingPillar) {
            CameraPhotoPicker(
                onPicked: { data in
                    isCapturingPillar = false
                    readPillar(data)
                },
                onCancel: { isCapturingPillar = false }
            )
            .ignoresSafeArea()
        }
        // Out of process, so no photo-library permission is requested or declared.
        .photosPicker(
            isPresented: $isPickingPillarFromLibrary,
            selection: $pillarLibraryItem,
            matching: .images,
            photoLibrary: .shared()
        )
        .onChange(of: pillarLibraryItem) { _, item in
            guard let item else { return }
            Task {
                defer { pillarLibraryItem = nil }
                guard let data = try? await item.loadTransferable(type: Data.self) else { return }
                readPillar(data)
            }
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

private extension HomeView {
    /// `사진으로 입력` on the empty card (docs/02 §6a).
    ///
    /// The album stays on offer beside the camera: the pillar may already have been
    /// photographed a minute ago, and a button that names the camera is not a promise never
    /// to show the library. Where there is no camera at all — the simulator, or access turned
    /// off — the library is the only entry and the question is skipped.
    func beginPillarEntry() {
        // Loaded while the user frames the shot rather than after they take it: the first
        // Korean recognition costs seconds (`PillarTextReading.prepare`).
        Task { await pillarReader.prepare() }
        let sources = ParkingPhotoSource.available
        if sources.count == 1, let only = sources.first {
            presentPillarSource(only)
        } else {
            isChoosingPillarSource = true
        }
    }

    func presentPillarSource(_ source: ParkingPhotoSource) {
        switch source {
        case .camera: isCapturingPillar = true
        case .library: isPickingPillarFromLibrary = true
        }
    }

    /// §6a: never throws, never explains. A floor, or nothing at all because there was no
    /// text or the read timed out — either way the same form opens.
    func readPillar(_ imageData: Data) {
        isReadingPillar = true
        pillarPhotoData = imageData
        Task {
            pillarReading = await pillarReader.read(imageData)
            isReadingPillar = false
            isManualSheetPresented = true
        }
    }

    /// The photo belongs to the sheet that was opened with it, not to the next one.
    func clearPillarEntry() {
        pillarPhotoData = nil
        pillarReading = nil
    }
}

/// The pending guess, on home.
///
/// One card, not a banner and not an alert: docs/10 §7 makes this something the user
/// answers when they choose to, and §7a keeps the answering on its own screen. The copy is
/// docs/02 §5's, unchanged — the row may not state the parking as settled any more than
/// the notification may.
private struct PendingCandidateCard: View {
    let candidate: ParkingCandidate
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: PKSpacing.l) {
                PKIconChip("questionmark.circle", tint: .primary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(CandidateNotificationCopy.title)
                        .font(PKTypography.row)
                        .foregroundStyle(PKColor.textPrimary)
                    Text(ParkingDateText.time(candidate.detectedAt))
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
        .buttonStyle(PKSurfaceButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// docs/02 §8's empty home.
private struct EmptyParkingCard: View {
    let onSaveManually: () -> Void
    let onPhotoEntry: () -> Void

    var body: some View {
        PKCard {
            VStack(alignment: .leading, spacing: PKSpacing.l) {
                Text("현재 저장된 주차 위치가 없어요.")
                    .font(PKTypography.sectionTitle)
                    .foregroundStyle(PKColor.textPrimary)
                Text("주차하면 주차핀이 알려드릴게요.\n지금 바로 직접 저장할 수도 있어요.")
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
                Button("직접 저장", action: onSaveManually)
                    .buttonStyle(PKPrimaryButtonStyle())
                // Secondary, under the one emphasised CTA: the design harness allows a
                // single primary per screen, and this is the same pairing the confirmation
                // screen uses.
                Button {
                    onPhotoEntry()
                } label: {
                    Label("사진으로 입력", systemImage: "camera")
                }
                .buttonStyle(PKOutlineButtonStyle())
            }
            .padding(PKSpacing.xl)
        }
    }
}

/// One of the tappable rows under the parking card.
private struct HomeActionRow: View {
    let icon: String
    let title: String
    let subtitle: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: PKSpacing.l) {
                PKIconChip(icon)
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
                // One surface for the whole preview, divided. Three rows each on their
                // own card is the panel stack the design harness rules out.
                PKCard(radius: PKRadius.row) {
                    VStack(spacing: 0) {
                        ForEach(Array(sessions.enumerated()), id: \.element.id) { index, session in
                            if index > 0 {
                                Divider()
                                    .overlay(PKColor.divider)
                                    .padding(.leading, PKSpacing.l)
                            }
                            Button { onSelect(session) } label: {
                                ParkingHistoryRow(session: session, style: .compact)
                            }
                            .buttonStyle(PKGroupedRowButtonStyle())
                        }
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
