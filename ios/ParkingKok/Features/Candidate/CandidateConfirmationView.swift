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
/// [ B1 ]  [ B2 ]  [ B3 ]  [ 직접 입력 ]
///
/// 주차 아님
/// ```
///
/// ### What is deliberately absent
/// No map, no address, no coordinate, and no guess at the floor. The engine does not know
/// which floor you are on, and docs/09 §9 keeps location off this surface — so there is
/// nothing here for either to be rendered from.
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
            floorChoices
                .pkEntrance(1)
            rejection
                .pkEntrance(2)
        }
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $isManualEntryPresented, onDismiss: manualEntryDismissed) {
            ManualParkingSheet(
                model: parking,
                confirming: candidate,
                candidates: candidates,
                suggestion: pillarSuggestion
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
            Text(Self.savedText)
                .font(PKTypography.supporting)
                .foregroundStyle(PKColor.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
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
                Button(Self.manualEntryTitle) { openManualEntry(with: nil) }
                    // The same weight as a quick pick. §7a ranks it after them, and rank
                    // here is order and label, not a second colour — the design harness
                    // allows one accent per screen and the picks already spend it.
                    .buttonStyle(PKSoftButtonStyle())
                if ParkingPhotoSource.available.contains(.camera) {
                    pillarButton
                }
            }
        }
    }

    /// docs/02 §6a: "The most valuable place to offer it is the confirmation screen: the
    /// user is standing at the pillar when the prompt arrives."
    ///
    /// `사진으로 입력`, not `사진 추가` — the photo is read and discarded, never stored.
    /// FR-007's one saved photo belongs to a record, and there is no record here yet;
    /// promising storage in the label and not delivering it would be the worse mistake.
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
        .buttonStyle(PKSoftButtonStyle())
        .disabled(isReadingPillar)
        .accessibilityLabel(Self.pillarEntryTitle)
    }

    /// §6a: never throws, never explains. Whatever comes back — a floor, or nothing at
    /// all because there was no text, the parse failed, or the read ran out of time —
    /// opens the same form.
    private func readPillar(_ imageData: Data) {
        isReadingPillar = true
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

    /// §7a: "A text button, full width, under the choices — reachable without a scroll on
    /// the smallest supported screen, and never hidden behind a menu or an X in a corner."
    ///
    /// Plain text rather than a filled or red button. It is the honest answer to a guess,
    /// not a destructive act, and dressing it as a warning would push people towards
    /// confirming something that did not happen — which is the one outcome the detector
    /// learns nothing from.
    private var rejection: some View {
        VStack(spacing: 0) {
            Divider()
                .overlay(PKColor.divider)
            Button(CandidateNotificationCopy.notParkingTitle) { reject() }
                .font(PKTypography.row)
                .foregroundStyle(PKColor.textPrimary)
                .frame(maxWidth: .infinity, minHeight: PKSize.minimumTouchTarget)
                .padding(.vertical, PKSpacing.s)
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
        guard candidates.pending?.id != candidate.id else { return }
        dismiss()
    }

    private func dismiss() {
        path.removeAll { $0 == .candidateConfirmation(id: candidate.id) }
    }

    /// §7a's second line, verbatim. Not the notification's body — the notification says
    /// what was saved, this says it about a moment the user is now looking at.
    static let savedText = "마지막 위치를 저장했어요."
    static let manualEntryTitle = "직접 입력"
    static let pillarEntryTitle = "사진으로 입력"
}
