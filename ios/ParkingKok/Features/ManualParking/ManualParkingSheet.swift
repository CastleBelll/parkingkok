import SwiftUI

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

    init(model: ParkingModel, editing: ParkingSession? = nil) {
        self.model = model
        self.editing = editing
    }

    var body: some View {
        NavigationStack {
            Form {
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
            .navigationTitle(editing == nil ? "주차 직접 저장" : "주차 정보 수정")
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
        guard let editing else { return }
        draft = ManualParkingDraft(
            floorText: editing.floor?.raw ?? "",
            zone: editing.zone ?? "",
            spot: editing.spot ?? "",
            memo: editing.memo ?? ""
        )
    }

    private func save() {
        isSaving = true
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
