import SwiftUI

/// docs/10_DESIGN_UX_SPEC.md §7a, which fixes this screen's shape because "two platforms
/// building from a tone of voice would each invent a layout".
///
/// ```text
/// 주차한 것 같아요
///
/// 오후 8:14
/// 마지막 위치를 저장했어요.
///
/// 마지막으로 확인된 위치
/// [ map ]  약 18m 이내
///
/// [ B1 ]  [ B2 ]  [ B3 ]
/// [ 사진으로 입력 ]  [ 직접 입력 ]
/// ─────────────────────────
/// 주차 아님
/// ```
///
/// ### What is deliberately absent
/// No address, and no guess at the floor. The engine does not know which floor you are on,
/// and an address is a claim this app is not entitled to make about a fix taken on the way
/// into a garage.
///
/// ### Where, though, is present
/// An earlier draft of this file said the screen shows no location at all "because docs/09
/// §9 keeps it off this surface". That citation was wrong: §9 is Google RTDN security and
/// says nothing about location. The screen was left claiming 마지막 위치를 저장했어요 while
/// refusing to say which — so §7a now puts the fix under FR-008's `마지막으로 확인된 위치`,
/// and `위치 없음` in the same place when there is none.
///
/// ### Why it is pushed and not presented
/// §7a: "A dialog that cannot be dismissed is how apps trap people, and this one is a
/// guess the user may simply not want to answer right now." Backing out leaves the
/// candidate pending until it expires.
struct CandidateConfirmationView: View {
    private let candidate: ParkingCandidate
    @Bindable private var candidates: CandidateModel
    @Bindable private var parking: ParkingModel
    @Binding private var path: [AppRoute]
    /// docs/02 §6a's on-device read. Injected so the screen can be exercised without a
    /// camera or a Vision model.
    private let pillarReader: any PillarTextReading

    @State private var isManualEntryPresented = false
    @State private var isCapturingPillar = false
    @State private var isReadingPillar = false
    /// What the last photo offered, handed to the sheet and cleared with it. `nil` and
    /// `PillarReading.none` are the same screen — §6a's "the form opens exactly as it does
    /// today, empty".
    @State private var pillarSuggestion: PillarReading?
    @State private var pillarPhotoData: Data?

    init(
        candidate: ParkingCandidate,
        candidates: CandidateModel,
        parking: ParkingModel,
        path: Binding<[AppRoute]>,
        pillarReader: any PillarTextReading = VisionPillarTextReader()
    ) {
        self.candidate = candidate
        self.candidates = candidates
        self.parking = parking
        _path = path
        self.pillarReader = pillarReader
    }

    var body: some View {
        PKScreen {
            header
                .pkEntrance(0)
            lastKnownLocation
                .pkEntrance(1)
            floorChoices
                .pkEntrance(2)
            rejection
                .pkEntrance(3)
        }
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $isManualEntryPresented, onDismiss: manualEntryDismissed) {
            ManualParkingSheet(
                model: parking,
                confirming: candidate,
                candidates: candidates,
                suggestion: pillarSuggestion,
                pillarPhoto: pillarPhotoData
            )
        }
        .task { readPillarFixtureIfRequested() }
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
    }

    /// §7a: "The uncertainty comes first and is the largest thing on the screen. Then when
    /// it happened".
    private var header: some View {
        VStack(alignment: .leading, spacing: PKSpacing.m) {
            Text(CandidateNotificationCopy.title)
                .font(PKTypography.screenTitle)
                .foregroundStyle(PKColor.textPrimary)
            Text(ParkingDateText.time(candidate.detectedAt))
                .font(PKTypography.heroSupport)
                .foregroundStyle(PKColor.textPrimary)
            // Only where it is true. With no fix this line used to promise a saved
            // location the screen then could not name.
            if mapPoint != nil {
                Text(Self.savedText)
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    /// §7a "where": FR-008's wording over the same thumbnail the home hero draws, accuracy
    /// circle and all. The circle is the point — the user is standing there deciding
    /// whether the app caught the right spot, and a bare pin would overstate what it knows.
    private var lastKnownLocation: some View {
        VStack(alignment: .leading, spacing: PKSpacing.s) {
            Text(ParkingMapPoint.label)
                .font(PKTypography.caption)
                .foregroundStyle(PKColor.textSecondary)
            if let mapPoint {
                HStack(spacing: PKSpacing.m) {
                    ParkingMapThumbnail(point: mapPoint, zoneText: nil)
                    Text(mapPoint.accuracyText)
                        .font(PKTypography.supporting)
                        .foregroundStyle(PKColor.textSecondary)
                }
            } else {
                // §7a: the same place, not a hidden row. A drive that ended underground
                // with no fix is ordinary, and saying so is what keeps the screen honest.
                Text(Self.noLocationText)
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// `nil` when the drive produced no fix worth keeping (§6), or one no map can draw.
    private var mapPoint: ParkingMapPoint? {
        candidate.lastReliableLocation.flatMap(ParkingMapPoint.init)
    }

    /// §7a: three quick picks and `직접 입력`.
    ///
    /// The mock draws all four on one line. They are split across two here because four
    /// touch targets do not fit the smallest supported screen at a legible size, and §7a's
    /// own rule — `주차 아님` reachable without a scroll — is the one that has to win. The
    /// ranking the mock fixes is unchanged: picks first, `직접 입력` after them.
    private var floorChoices: some View {
        VStack(spacing: PKSpacing.m) {
            let picks = candidates.floorPicks
            if !picks.isEmpty {
                HStack(spacing: PKSpacing.m) {
                    ForEach(picks, id: \.self) { floor in
                        Button(floor.displayText) { confirm(floor: floor) }
                            .buttonStyle(PKSoftButtonStyle())
                            .accessibilityLabel(floor.accessibilityText)
                    }
                }
            }
            HStack(spacing: PKSpacing.m) {
                // Photo first (docs/10 §7a): it is the faster of the two and typing less
                // is the point. It drops out entirely where there is no camera, and
                // 직접 입력 takes the width on its own.
                if ParkingPhotoSource.available.contains(.camera) {
                    pillarButton
                }
                Button(Self.manualEntryTitle) { openManualEntry(with: nil) }
                    // Outlined, not tinted. §7a ranks these after the picks, and five
                    // identically tinted blocks would have given the screen no ranking at
                    // all — the border keeps them legible as controls while the picks keep
                    // the screen's one accent (CLAUDE.md design harness).
                    .buttonStyle(PKOutlineButtonStyle())
            }
        }
    }

    /// docs/02 §6a: "The most valuable place to offer it is the confirmation screen: the
    /// user is standing at the pillar when the prompt arrives."
    ///
    /// The photo is kept, not read and thrown away (docs/02 §6a): it is the pillar photo
    /// the user would otherwise have to take a second time from the detail screen. There
    /// is no record to attach it to yet, so it rides along to the sheet and is attached by
    /// the confirmation that creates one.
    ///
    /// Hidden entirely where there is no camera (the simulator, or camera access turned
    /// off), because §7a's screen has no room for a control that cannot work.
    private var pillarButton: some View {
        Button {
            isCapturingPillar = true
            // The model loads while the user frames the shot rather than after they take
            // it — see `PillarTextReading.prepare`.
            Task { await pillarReader.prepare() }
        } label: {
            if isReadingPillar {
                // Only while the read is in flight, and it goes when the sheet opens —
                // §6a forbids "a spinner left behind", not a control that says it is busy.
                ProgressView()
            } else {
                Label(Self.pillarEntryTitle, systemImage: "camera")
            }
        }
        .buttonStyle(PKOutlineButtonStyle())
        .disabled(isReadingPillar)
        .accessibilityLabel(Self.pillarEntryTitle)
    }

    /// §6a: never throws, never explains. Whatever comes back — a floor, or nothing at
    /// all because there was no text, the parse failed, or the read ran out of time —
    /// opens the same form.
    private func readPillar(_ imageData: Data) {
        isReadingPillar = true
        pillarPhotoData = imageData
        Task {
            let reading = await pillarReader.read(imageData)
            isReadingPillar = false
            openManualEntry(with: reading)
        }
    }

    /// Capture hook, and a no-op in every shipped build.
    ///
    /// The sibling of `PK_OPEN_CANDIDATE`: a headless device has no way to press a camera
    /// shutter, so this feeds the fixture pillar through the **same** reader and the same
    /// sheet a real capture goes through.
    ///
    /// ```sh
    ///   --environment-variables '{"PK_INJECT_CANDIDATE":"medium","PK_OPEN_CANDIDATE":"1","PK_PILLAR_FIXTURE":"1"}'
    /// ```
    private func readPillarFixtureIfRequested() {
        #if PK_DEV
            guard ProcessInfo.processInfo.environment["PK_PILLAR_FIXTURE"] == "1",
                  let fixture = ParkingSampleSeed.placeholderPhotoData()
            else { return }
            Task {
                // The camera would have loaded the model by now; a capture that skipped
                // this would be photographing the cold path and calling it the product.
                await pillarReader.prepare()
                readPillar(fixture)
            }
        #endif
    }

    private func openManualEntry(with reading: PillarReading?) {
        pillarSuggestion = reading
        isManualEntryPresented = true
    }

    /// §7a: "Directly under the choices, separated from them by a divider, and not pinned
    /// to the bottom of the screen — a control floating alone in empty space does not read
    /// as a control at all."
    ///
    /// Plain text rather than a filled or red button. It is the honest answer to a guess,
    /// not a destructive act, and dressing it as a warning would push people towards
    /// confirming something that did not happen — which is the one outcome the detector
    /// learns nothing from. Android draws the identical pair.
    private var rejection: some View {
        VStack(spacing: PKSpacing.m) {
            Divider()
                .overlay(PKColor.divider)
            Button(CandidateNotificationCopy.notParkingTitle) { reject() }
                .buttonStyle(PKOutlineButtonStyle(tint: PKColor.textSecondary))
        }
    }

    private func confirm(floor: FloorValue) {
        guard candidates.confirm(candidate, floor: floor) else { return }
        dismiss()
    }

    private func reject() {
        candidates.reject(candidate)
        // §7a: "Rejecting returns to where the user was. It never asks why."
        dismiss()
    }

    /// The sheet answers on its own, so this only has to notice that it did.
    private func manualEntryDismissed() {
        // The read is spent either way: cancelled, it must not reappear behind the next
        // 직접 입력, and saved, it is already in the record.
        pillarSuggestion = nil
        pillarPhotoData = nil
        guard candidates.pending?.id != candidate.id else { return }
        dismiss()
    }

    private func dismiss() {
        path.removeAll { $0 == .candidateConfirmation(id: candidate.id) }
    }

    /// §7a's second line, verbatim. Not the notification's body — the notification says
    /// what was saved, this says it about a moment the user is now looking at.
    static let savedText = "마지막 위치를 저장했어요."
    /// §7a's `위치 없음`, the same words Android's `location_none` carries.
    static let noLocationText = "위치 없음"
    static let manualEntryTitle = "직접 입력"
    static let pillarEntryTitle = "사진으로 입력"
}
