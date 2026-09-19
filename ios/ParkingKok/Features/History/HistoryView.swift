import SwiftUI

/// The full record list (`design-references/04-history-list.png`).
///
/// FR-009's "free 5건" gate is deliberately not applied: there is no subscription in the
/// build, so capping the list would show the restriction without the thing it restricts.
/// Every stored record is listed.
struct HistoryView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @Bindable private var model: ParkingModel
    @Binding private var path: [AppRoute]

    @State private var filter: HistoryFilter = .all
    @State private var isConfirmingDeleteAll = false

    init(model: ParkingModel, path: Binding<[AppRoute]>) {
        self.model = model
        _path = path
    }

    var body: some View {
        PKScreen {
            filterChips
                .pkEntrance(0)
            MonthlySummaryCard(summary: HistorySummary(sessions: model.completedSessions, now: model.now))
                .pkEntrance(1)

            if filtered.isEmpty {
                Text(filter == .all ? "아직 저장된 기록이 없어요." : "해당 조건의 기록이 없어요.")
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, PKSpacing.xxl)
            } else {
                // The stagger is on the group, not on each row: `04-history-list.png` can
                // run to dozens of records, and animating them individually would mean a
                // wait proportional to the history's length.
                // One surface for the whole list, divided. A card per record turned the
                // screen into a stack of floating panels — the AI dashboard the design
                // harness rules out, and what home's preview was fixed for.
                PKCard(radius: PKRadius.row) {
                    VStack(spacing: 0) {
                        ForEach(Array(filtered.enumerated()), id: \.element.id) { index, session in
                            if index > 0 {
                                Divider()
                                    .overlay(PKColor.divider)
                                    .padding(.leading, PKSpacing.l)
                            }
                            Button { path.append(.parkingDetail(id: session.id)) } label: {
                                ParkingHistoryRow(session: session, style: .full)
                            }
                            .buttonStyle(PKGroupedRowButtonStyle())
                        }
                    }
                }
                .pkEntrance(2)

                Button("기록 전체 삭제", role: .destructive) { isConfirmingDeleteAll = true }
                    .font(PKTypography.row)
                    .tint(PKColor.danger)
                    .frame(maxWidth: .infinity, minHeight: PKSize.minimumTouchTarget)
                    .padding(.top, PKSpacing.s)
                    .pkEntrance(3)
            }

            PKBrandFooter()
        }
        .navigationTitle("주차 기록")
        .navigationBarTitleDisplayMode(.large)
        .onAppear { model.refresh() }
        .confirmationDialog(
            "주차 기록을 모두 삭제할까요?",
            isPresented: $isConfirmingDeleteAll,
            titleVisibility: .visible
        ) {
            Button("전체 삭제", role: .destructive) {
                Task { await model.deleteAllLocalData() }
            }
            Button("취소", role: .cancel) {}
        } message: {
            Text("진행 중인 주차를 포함해 이 기기의 모든 주차 기록이 사라져요. 되돌릴 수 없어요.")
        }
    }

    private var filtered: [ParkingSession] {
        model.completedSessions.filter(filter.matches)
    }

    private var filterChips: some View {
        HStack(spacing: PKSpacing.s) {
            ForEach(HistoryFilter.allCases, id: \.self) { option in
                Button {
                    pkWithAnimation(PKMotion.selection, reduceMotion: reduceMotion) {
                        filter = option
                    }
                } label: {
                    Text(option.title)
                }
                .buttonStyle(PKChipButtonStyle(isSelected: option == filter))
                // Selection is a colour swap in the mock; VoiceOver needs it said.
                .accessibilityAddTraits(option == filter ? [.isButton, .isSelected] : .isButton)
            }
        }
    }
}

/// docs/19: the list is skimmed by date and place, with 자동 감지 distinguishable.
enum HistoryFilter: CaseIterable {
    case all
    case detected
    case manual

    var title: String {
        switch self {
        case .all: "전체"
        case .detected: "자동 감지"
        case .manual: "직접 저장"
        }
    }

    func matches(_ session: ParkingSession) -> Bool {
        switch self {
        case .all: true
        case .detected: session.source == .detected
        case .manual: session.source == .manual
        }
    }
}

/// The "이번 달 N회" card at the top of the list.
struct HistorySummary: Equatable {
    let thisMonth: Int
    let lastMonth: Int

    init(sessions: [ParkingSession], now: Date, calendar: Calendar = .autoupdatingCurrent) {
        let previousMonth = calendar.date(byAdding: .month, value: -1, to: now)
        thisMonth = sessions.count { calendar.isDate($0.startedAt, equalTo: now, toGranularity: .month) }
        lastMonth = previousMonth.map { month in
            sessions.count { calendar.isDate($0.startedAt, equalTo: month, toGranularity: .month) }
        } ?? 0
    }

    /// `nil` in the first month of use, when there is nothing to compare against and the
    /// mock's cheerful "지난 달보다 3회 더" would be comparing to a month that never ran.
    var comparisonText: String? {
        guard lastMonth > 0 else { return nil }
        let difference = thisMonth - lastMonth
        if difference > 0 {
            return "지난 달보다 \(difference)회 더 주차했어요."
        }
        if difference < 0 {
            return "지난 달보다 \(-difference)회 적어요."
        }
        return "지난 달과 같아요."
    }
}

private struct MonthlySummaryCard: View {
    let summary: HistorySummary

    var body: some View {
        PKCard(radius: PKRadius.row) {
            HStack(spacing: PKSpacing.l) {
                PKIconChip("chart.bar.fill")
                VStack(alignment: .leading, spacing: PKSpacing.xs) {
                    Text("이번 달")
                        .font(PKTypography.supporting)
                        .foregroundStyle(PKColor.textSecondary)
                    Text("\(summary.thisMonth)회 주차 기록")
                        .font(PKTypography.sectionTitle)
                        .foregroundStyle(PKColor.textPrimary)
                    if let comparison = summary.comparisonText {
                        Text(comparison)
                            .font(PKTypography.supporting)
                            .foregroundStyle(PKColor.textSecondary)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(PKSpacing.l)
        }
        .accessibilityElement(children: .combine)
    }
}
