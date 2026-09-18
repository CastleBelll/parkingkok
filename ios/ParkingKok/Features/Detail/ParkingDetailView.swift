import SwiftUI

/// One parking in full (`design-references/03-parking-detail.png`).
///
/// The mock's map and photo panels are absent: FR-008 (map) and FR-007 (photo) are not
/// in this milestone, and a placeholder map would claim a precision the app does not
/// have. What the map was there to communicate — how well the location is known — is
/// stated instead, with FR-008's `마지막으로 확인된 위치` wording.
struct ParkingDetailView: View {
    @Bindable private var model: ParkingModel
    private let sessionID: UUID

    @Environment(\.dismiss) private var dismiss
    @State private var isEditing = false
    @State private var isConfirmingDelete = false
    @State private var displayNow = Date()

    init(model: ParkingModel, sessionID: UUID) {
        self.model = model
        self.sessionID = sessionID
    }

    var body: some View {
        PKScreen {
            if let session {
                summaryCard(session)
                factsCard(session)
                actions(session)
            } else {
                Text("기록을 찾을 수 없어요.")
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
            }
            PKBrandFooter()
        }
        .navigationTitle("주차 위치")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let session, session.isActive {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("수정") { isEditing = true }
                }
            }
        }
        .onAppear { displayNow = model.now }
        .sheet(isPresented: $isEditing) {
            if let session {
                ManualParkingSheet(model: model, editing: session)
            }
        }
        .confirmationDialog("이 주차 기록을 삭제할까요?", isPresented: $isConfirmingDelete, titleVisibility: .visible) {
            Button("삭제", role: .destructive) {
                model.delete(id: sessionID)
                dismiss()
            }
            Button("취소", role: .cancel) {}
        } message: {
            Text("삭제한 기록은 되돌릴 수 없어요.")
        }
    }

    /// Read through the model so an edit made in the sheet is reflected here.
    private var session: ParkingSession? {
        if let active = model.activeSession, active.id == sessionID {
            return active
        }
        return model.completedSessions.first { $0.id == sessionID }
    }

    private func summaryCard(_ session: ParkingSession) -> some View {
        PKCard {
            VStack(alignment: .leading, spacing: PKSpacing.xs) {
                Text(session.isActive ? "현재 주차 위치" : "지난 주차 기록")
                    .font(PKTypography.caption)
                    .foregroundStyle(PKColor.textSecondary)

                PKHeroFloorText(session.floor?.displayText ?? "층 미입력")
                    .accessibilityLabel(
                        "\(session.isActive ? "현재 주차 위치" : "지난 주차 기록"), "
                            + (session.floor?.accessibilityText ?? "층 미입력")
                    )

                if let place = [session.zone, session.spot].compactMap(\.self).nonEmptyJoined(" · ") {
                    Text(place)
                        .font(PKTypography.heroSupport)
                        .foregroundStyle(PKColor.textPrimary)
                }

                Text(durationText(session))
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)

                if let memo = session.memo {
                    Text(memo)
                        .font(PKTypography.supporting)
                        .foregroundStyle(PKColor.textPrimary)
                        .padding(.top, PKSpacing.s)
                }
            }
            .padding(PKSpacing.xl)
        }
    }

    private func durationText(_ session: ParkingSession) -> String {
        guard let endedAt = session.endedAt else {
            return ParkingElapsed.describeActive(from: session.startedAt, to: displayNow)
        }
        return "\(ParkingElapsed.describeDuration(from: session.startedAt, to: endedAt)) 주차했어요"
    }

    private func factsCard(_ session: ParkingSession) -> some View {
        PKCard {
            VStack(spacing: 0) {
                DetailFactRow(
                    icon: "clock",
                    title: "주차 시작",
                    value: ParkingDateText.dayAndTime(session.startedAt, now: displayNow)
                )
                divider
                if let endedAt = session.endedAt {
                    DetailFactRow(
                        icon: "flag.checkered",
                        title: "주차 종료",
                        value: ParkingDateText.dayAndTime(endedAt, now: displayNow)
                    )
                    divider
                }
                DetailFactRow(
                    icon: "square.and.pencil",
                    title: "저장 방식",
                    value: session.source == .detected ? "자동 감지" : "직접 저장"
                )
                divider
                DetailFactRow(
                    icon: "scope",
                    title: "마지막으로 확인된 위치",
                    value: locationText(session)
                )
            }
            .padding(.vertical, PKSpacing.xs)
        }
    }

    /// FR-008 forbids wording that implies the exact car position, and the accuracy is
    /// the honest version of it. No location at all is the FR-001 case and says so.
    private func locationText(_ session: ParkingSession) -> String {
        guard let location = session.location else {
            return "저장 안 됨"
        }
        return "약 \(Int(location.horizontalAccuracy.rounded()))m 이내"
    }

    private var divider: some View {
        Rectangle()
            .fill(PKColor.divider)
            .frame(height: PKSize.hairline)
            .padding(.leading, PKSpacing.xxl + PKSpacing.l)
    }

    @ViewBuilder
    private func actions(_ session: ParkingSession) -> some View {
        if session.isActive {
            PKPrimaryActionButton(
                title: "주차 종료",
                subtitle: "주차를 종료하고 기록을 저장합니다",
                systemImage: "flag.checkered"
            ) {
                model.endActiveParking()
                dismiss()
            }
        }
        Button("기록 삭제", role: .destructive) { isConfirmingDelete = true }
            .font(PKTypography.row)
            .tint(PKColor.danger)
            .frame(maxWidth: .infinity, minHeight: PKSize.minimumTouchTarget)
    }
}

private struct DetailFactRow: View {
    let icon: String
    let title: String
    let value: String

    var body: some View {
        HStack(spacing: PKSpacing.m) {
            Image(systemName: icon)
                .font(.body)
                .foregroundStyle(PKColor.textSecondary)
                .frame(width: PKSpacing.xxl)
                .accessibilityHidden(true)
            Text(title)
                .font(PKTypography.supporting)
                .foregroundStyle(PKColor.textSecondary)
            Spacer(minLength: PKSpacing.s)
            Text(value)
                .font(PKTypography.row)
                .foregroundStyle(PKColor.textPrimary)
                .multilineTextAlignment(.trailing)
        }
        .padding(.horizontal, PKSpacing.l)
        .padding(.vertical, PKSpacing.m)
        .frame(minHeight: PKSize.minimumTouchTarget)
        .accessibilityElement(children: .combine)
    }
}

extension [String] {
    /// Joins, or `nil` when there was nothing to join.
    func nonEmptyJoined(_ separator: String) -> String? {
        isEmpty ? nil : joined(separator: separator)
    }
}
