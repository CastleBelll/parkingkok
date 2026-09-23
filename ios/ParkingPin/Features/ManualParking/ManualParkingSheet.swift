import SwiftUI
import UIKit

/// Manual parking entry — FR-001, and the screen that has to work when nothing else does.
///
/// No permission is requested here and none is checked. The location, if any, is
/// whatever the detection stack already had; with every permission denied the save still
/// completes and the record still appears in history. That is the hard constraint from
/// CLAUDE.md ("Manual parking은 항상 가능해야 한다"), and it is why this sheet talks to
/// `ParkingModel` and nothing else.
///
/// Doubles as the editor for an existing parking (docs/02 §3 "Edit Floor/Zone"), so the
/// fields and their validation exist once.
struct ManualParkingSheet: View {
    @Bindable private var model: ParkingModel
    /// `nil` to create; a session to edit.
    private let editing: ParkingSession?
    /// Non-nil when this sheet is docs/10 §7a's `직접 입력`: the same form, but saving
    /// confirms a detection candidate instead of creating a manual record. The screen is
    /// shared on purpose — §7a says "opens the existing manual entry", and a second form
    /// would be one more place for the floor rules to drift.
    private let confirmation: CandidateConfirmationTarget?
    /// docs/02 §6a: what a pillar photo offered, if anything. Pre-filled and focused,
    /// **never saved on its own** — "a misread `B3` as `83` that silently became the
    /// record would be worse than typing", so it arrives as text in a field the user is
    /// already looking at and goes no further until they press 저장.
    private let suggestion: PillarReading?

    /// The shot the pillar reading came from, kept so confirming attaches it to the record
    /// it creates (docs/02 §6a). Nil for every path that did not come from a camera.
    private let pillarPhoto: Data?

    @Environment(\.dismiss) private var dismiss
    @State private var draft = ManualParkingDraft()
    @State private var isSaving = false
    @FocusState private var focusedField: Field?

    private enum Field: Hashable {
        case floor
        case zone
        case spot
        case memo
    }

    init(
        model: ParkingModel,
        editing: ParkingSession? = nil,
        suggestion: PillarReading? = nil,
        pillarPhoto: Data? = nil
    ) {
        self.pillarPhoto = pillarPhoto
        self.model = model
        self.editing = editing
        self.suggestion = suggestion
        confirmation = nil
    }

    /// docs/10 §7a `직접 입력`. "Prefilled with nothing, and saving there confirms."
    init(
        model: ParkingModel,
        confirming candidate: ParkingCandidate,
        candidates: CandidateModel,
        suggestion: PillarReading? = nil,
        pillarPhoto: Data? = nil
    ) {
        self.pillarPhoto = pillarPhoto
        self.model = model
        editing = nil
        self.suggestion = suggestion
        confirmation = CandidateConfirmationTarget(candidate: candidate, candidates: candidates)
    }

    var body: some View {
        NavigationStack {
            Form {
                if let pillarPhoto, let image = UIImage(data: pillarPhoto) {
                    pillarSection(image)
                }
                Section {
                    TextField("예: B3, 지하 3층, 3F", text: $draft.floorText)
                        .focused($focusedField, equals: .floor)
                        .submitLabel(.next)
                } header: {
                    Text("층")
                } footer: {
                    Text(floorFooter)
                }

                Section("구역 · 자리") {
                    TextField("예: A구역", text: $draft.zone)
                        .focused($focusedField, equals: .zone)
                    TextField("예: 142", text: $draft.spot)
                        .focused($focusedField, equals: .spot)
                }

                Section {
                    TextField("메모", text: $draft.memo, axis: .vertical)
                        .lineLimit(2 ... 4)
                        .focused($focusedField, equals: .memo)
                } header: {
                    Text("메모")
                } footer: {
                    // The trust message docs/19 asks settings and footers to repeat.
                    Text("층·구역·메모와 위치는 이 기기에만 저장되며 서버로 전송하지 않습니다.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(PKColor.background)
            .navigationTitle(navigationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("취소") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("저장") { save() }
                        .disabled(isSaving)
                }
            }
            .onAppear(perform: loadDraft)
        }
        .presentationDetents([.medium, .large])
    }

    /// Never `주차 완료` — docs/10 §7 forbids stating a detection as settled, and this
    /// sheet is reached from a guess as often as from a deliberate save.
    private var navigationTitle: String {
        if confirmation != nil {
            return "주차 정보 입력"
        }
        if editing != nil {
            return "주차 정보 수정"
        }
        // Arriving from `사진으로 입력` and being met by a screen titled 직접 저장 reads as
        // the photo having been ignored — it was, reported from the device as "사진 올렸는데
        // 왜 직접 입력 화면이 나와?". The destination is right; it just never said so.
        return pillarPhoto == nil ? "주차 직접 저장" : "사진으로 저장"
    }

    /// What the photo was, and what came of it.
    ///
    /// docs/02 §6a's "never explains" is about the *confirmation* screen, where the form was
    /// opening anyway and a failed read is indistinguishable from an ordinary blank form. A
    /// user who chose 사진으로 입력 is owed the other half: silence there cannot be told
    /// apart from a feature that does nothing.
    @ViewBuilder
    private func pillarSection(_ image: UIImage) -> some View {
        Section {
            HStack(spacing: PKSpacing.l) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: PKRadius.chip, style: .continuous))
                Text(pillarSummary)
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
            }
            .padding(.vertical, PKSpacing.xs)
        } footer: {
            Text("사진은 저장과 함께 이 기록에 남아요.")
        }
    }

    private var pillarSummary: String {
        let read = [suggestion?.floorText, suggestion?.zone, suggestion?.spot]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
        guard !read.isEmpty else {
            return "사진에서 층·구역을 찾지 못했어요. 직접 적어주세요."
        }
        return "사진에서 \(read.joined(separator: " · "))(을)를 읽었어요. 맞는지 확인해 주세요."
    }

    /// FR-005's accepted spellings, plus what happens to anything else.
    private var floorFooter: String {
        let parsed = FloorValue.parse(draft.floorText)
        switch parsed?.kind {
        case .basement, .ground:
            return "\(parsed?.displayText ?? "")(으)로 저장되고 -/+ 로 바꿀 수 있어요."
        case .freeText:
            // Honest about the trade: the text is kept, the stepper is not available.
            return "입력한 그대로 저장돼요. 숫자 층이 아니면 -/+ 로 바꿀 수 없어요."
        case nil:
            return "비워두면 나중에 입력할 수 있어요."
        }
    }

    private func loadDraft() {
        if let editing {
            draft = ManualParkingDraft(
                floorText: editing.floor?.raw ?? "",
                zone: editing.zone ?? "",
                spot: editing.spot ?? "",
                memo: editing.memo ?? ""
            )
        }
        applySuggestion()
    }

    /// docs/02 §6a: "the result is pre-filled into the fields the user was going to fill
    /// anyway, focused and editable".
    ///
    /// It never overwrites something the user already recorded — a photo is a guess and
    /// what is stored is not. On a record that already has a floor the read is simply
    /// dropped, which is also why the detail screen does not re-ask with a photo it can
    /// add nothing to.
    private func applySuggestion() {
        guard let suggestion else { return }
        // Every field the wall stated and the form has not (§6a "partial read → fill what
        // parsed, leave the rest blank"), each judged on its own: a pillar that names the
        // zone but not the floor still saves the user a line of typing.
        if let zone = suggestion.suggestedZone(over: draft.zone) {
            draft.zone = zone
        }
        if let spot = suggestion.suggestedSpot(over: draft.spot) {
            draft.spot = spot
        }
        guard let floorText = suggestion.suggestedFloorText(over: draft.floorText) else { return }
        draft.floorText = floorText
        // Focused, so the first thing the user can do is correct it.
        focusedField = .floor
    }

    private func save() {
        isSaving = true
        if let confirmation {
            // §7a: saving here *is* the confirmation. One path, so the record a quick pick
            // writes and the record this writes differ only in what the user typed.
            finish(
                succeeded: confirmation.candidates.confirm(
                    confirmation.candidate,
                    draft: draft,
                    pillarPhoto: pillarPhoto
                )
            )
            return
        }
        if var editing {
            editing.floor = FloorValue.parse(draft.floorText)
            editing.zone = draft.zone
            editing.spot = draft.spot
            editing.memo = draft.memo
            finish(succeeded: model.update(editing))
            return
        }
        // Creating needs the location lookup, which is async and best-effort.
        Task {
            let succeeded = await model.saveManualParking(draft)
            // The pillar photo is kept, not read and thrown away (docs/02 §6a): it is the
            // photo the user would otherwise have to take a second time from the detail
            // screen. The record it belongs to is the one the save just made active.
            if succeeded, let pillarPhoto, let id = model.activeSession?.id {
                _ = await model.attachPhoto(pillarPhoto, to: id)
            }
            finish(succeeded: succeeded)
        }
    }

    /// Stays open on failure so the user's typing is not thrown away with the sheet.
    private func finish(succeeded: Bool) {
        isSaving = false
        if succeeded {
            dismiss()
        }
    }
}

/// What `직접 입력` is answering.
///
/// A struct rather than two more stored properties so "this sheet is confirming" is one
/// optional that cannot be half-set.
struct CandidateConfirmationTarget {
    let candidate: ParkingCandidate
    let candidates: CandidateModel
}
