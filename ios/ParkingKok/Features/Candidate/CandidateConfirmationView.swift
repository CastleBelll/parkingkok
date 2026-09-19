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

    @State private var isManualEntryPresented = false

    init(
        candidate: ParkingCandidate,
        candidates: CandidateModel,
        parking: ParkingModel,
        path: Binding<[AppRoute]>
    ) {
        self.candidate = candidate
        self.candidates = candidates
        self.parking = parking
        _path = path
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
        .sheet(isPresented: $isManualEntryPresented, onDismiss: dismissIfAnswered) {
            ManualParkingSheet(model: parking, confirming: candidate, candidates: candidates)
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
            Button(Self.manualEntryTitle) { isManualEntryPresented = true }
                // The same weight as a quick pick. §7a ranks it after them, and rank here
                // is order and label, not a second colour — the design harness allows one
                // accent per screen and the picks already spend it.
                .buttonStyle(PKSoftButtonStyle())
        }
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
    private func dismissIfAnswered() {
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
}
