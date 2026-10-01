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
    /// The photo and what was read from it, as **one** value.
    ///
    /// They were two `@State`s and a shared `isManualSheetPresented`, and the sheet opened
    /// with the photo but without the reading: the diagnostics file showed the engine
    /// choosing `B2` while the screen said 사진에서 층을 찾지 못했어요. Presenting from the
    /// value itself removes the window in which only half of it is set.
    @State private var pillarEntry: PillarEntry?
    /// True while a pillar photo is being read: up to six seconds with the camera closed
    /// and nothing on screen to say why (audit 2026-10-01 L6).
    @State private var isReadingPillar = false
    private let pillarReader: any PillarTextReading = VisionPillarTextReader()
    /// Ticks once a minute so the elapsed line ages while the screen is open, without a
    /// timer that survives the screen.
    @State private var displayNow = Date()
    /// The parking whose §18 offer was applied or waved away; it is not asked again.
    @State private var usualSpotAnsweredFor: UUID?

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
                        onEditFloor: { isManualSheetPresented = true },
                        endPrompt: model.endPrompt,
                        isLocating: model.locatingSessionID == active.id,
                        onAcceptEnd: {
                            pkWithAnimation(PKMotion.sessionChange, reduceMotion: reduceMotion) {
                                _ = model.acceptEndProposal()
                            }
                        },
                        onKeepParking: {
                            pkWithAnimation(PKMotion.sessionChange, reduceMotion: reduceMotion) {
                                model.keepParking()
                            }
                        }
                    )
                    .pkEntrance(1)
                    if usualSpotAnsweredFor != active.id, let offer = model.usualSpot(for: active) {
                        UsualSpotCard(
                            offer: offer,
                            onApply: { applyUsualSpot(offer, to: active) },
                            onDismiss: { usualSpotAnsweredFor = active.id }
                        )
                        .pkEntrance(1)
                    }
                    HomeActionRow(
                        icon: "map",
                        title: "주차 위치 보기",
                        // What the row opens: the detail with its map, 길찾기 and photo.
                        subtitle: "지도, 길찾기, 사진을 한 번에 확인해요"
                    ) {
                        path.append(.parkingDetail(id: active.id))
                    }
                    .pkEntrance(2)
                    // One primary per screen (CLAUDE.md): while a departure proposal is
                    // pending, the card's own 주차 종료 is that primary — it ends the parking
                    // when the car left, which is what this button would get wrong.
                    if model.endPrompt == nil {
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
                    }
                } else {
                    // Shown beside a pending candidate too: hiding it left answering the
                    // guess as the only way to save a parking by hand (audit 2026-10-01).
                    EmptyParkingCard(
                        isReadingPhoto: isReadingPillar,
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
        .sheet(isPresented: $isManualSheetPresented) {
            ManualParkingSheet(model: model, editing: model.activeSession)
        }
        .sheet(item: $pillarEntry) { entry in
            ManualParkingSheet(
                model: model,
                editing: model.activeSession,
                suggestion: entry.reading,
                pillarPhoto: entry.photo
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
                guard let data = try? await item.loadTransferable(type: Data.self) else {
                    model.notePhotoUnreadable()
                    return
                }
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
    /// docs/02 §18: written on the tap, and only into what the parking left blank.
    func applyUsualSpot(_ offer: PillarReading, to session: ParkingSession) {
        usualSpotAnsweredFor = session.id
        var updated = session
        if updated.floor == nil, let floorText = offer.floorText {
            updated.floor = FloorValue.parse(floorText)
        }
        if (updated.zone ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, let zone = offer.zone {
            updated.zone = zone
        }
        _ = model.update(updated)
    }

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
        Task {
            // One assignment, which both presents the sheet and fills it. `.sheet(item:)`
            // rather than a boolean beside two other pieces of state: SwiftUI builds the
            // sheet from the value it was given, so there is no ordering left to get wrong.
            let reading = await pillarReader.read(imageData)
            isReadingPillar = false
            pillarEntry = PillarEntry(photo: imageData, reading: reading)
        }
    }
}

/// A photo and what the pillar reader made of it, kept together so the sheet cannot be
/// opened with one and not the other.
private struct PillarEntry: Identifiable {
    let id = UUID()
    let photo: Data
    let reading: PillarReading
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
    var isReadingPhoto = false
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
                    if isReadingPhoto {
                        HStack(spacing: PKSpacing.s) {
                            ProgressView()
                            Text("사진 읽는 중")
                        }
                    } else {
                        Label("사진으로 입력", systemImage: "camera")
                    }
                }
                .buttonStyle(PKOutlineButtonStyle())
                .disabled(isReadingPhoto)
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
                    // Caption text alone is a 16 pt target; the row gets the full minimum.
                    .frame(minHeight: PKSize.minimumTouchTarget)
                    .contentShape(.rect)
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

/// docs/02 §18 on home: last time's floor and zone at this car park, for the blanks of the
/// open parking. Nothing changes until `적용`; `아니요` retires it for this parking.
///
/// A soft button, not the primary style: home's one primary is `주차 종료`.
private struct UsualSpotCard: View {
    let offer: PillarReading
    let onApply: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        PKCard(radius: PKRadius.row) {
            VStack(alignment: .leading, spacing: PKSpacing.s) {
                Text("지난번 이 주차장에선 여기였어요")
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
                Text(offer.floorAndZoneLabel)
                    .font(PKTypography.sectionTitle)
                    .foregroundStyle(PKColor.textPrimary)
                HStack(spacing: PKSpacing.m) {
                    Button("아니요", action: onDismiss)
                        .buttonStyle(PKOutlineButtonStyle())
                    Button("적용", action: onApply)
                        .buttonStyle(PKSoftButtonStyle())
                }
            }
            .padding(PKSpacing.l)
        }
    }
}
