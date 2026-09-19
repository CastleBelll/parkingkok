import SwiftUI

/// docs/10 §7b — what the header's bell opens.
///
/// ```text
/// 알림
///
/// 주차한 것 같아요            오후 8:14
/// B3 · A구역 142 로 저장됨
///
/// 주차한 것 같아요            어제 오후 6:24
/// 주차 아님
///
/// 주차한 것 같아요            9월 17일
/// 응답 없음
/// ```
///
/// ### What is deliberately absent
/// No coordinate, no address, no map: §7b keeps this surface to the same rule the
/// notification itself follows, "the same surface a day later". Trace label prompts are a
/// diagnostics tool and are not notifications, so they are not here either.
///
/// One surface with dividers rather than a card per row — the design harness rules out
/// the stack of floating panels, and the history screen already settled this shape.
struct NotificationHistoryView: View {
    @Bindable private var candidates: CandidateModel
    @Binding private var path: [AppRoute]

    /// Read in `onAppear`, never in `body`: building the list touches the history file
    /// and docs/16 §5 keeps storage work out of render.
    @State private var items: [NotificationHistoryItem] = []
    @State private var now = Date()

    init(candidates: CandidateModel, path: Binding<[AppRoute]>) {
        self.candidates = candidates
        _path = path
    }

    var body: some View {
        PKScreen {
            if items.isEmpty {
                emptyState
                    .pkEntrance(0)
            } else {
                PKCard(radius: PKRadius.row) {
                    VStack(spacing: 0) {
                        ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                            if index > 0 {
                                Divider()
                                    .overlay(PKColor.divider)
                                    .padding(.leading, PKSpacing.l)
                            }
                            row(for: item)
                        }
                    }
                }
                .pkEntrance(0)
            }
        }
        .navigationTitle("알림")
        .navigationBarTitleDisplayMode(.large)
        .onAppear(perform: reload)
    }

    /// A row that goes somewhere is a button; one that does not is not.
    ///
    /// §7b: "One that was rejected or expired does nothing — it is history, and there is
    /// nothing left to act on. A row that does nothing must not look tappable." Which is
    /// why this is a branch on `destination` and not a disabled button: a disabled button
    /// still says there was something there.
    @ViewBuilder
    private func row(for item: NotificationHistoryItem) -> some View {
        if let destination = item.destination {
            Button { path.append(destination) } label: {
                NotificationHistoryRow(item: item, now: now)
            }
            .buttonStyle(PKGroupedRowButtonStyle())
        } else {
            NotificationHistoryRow(item: item, now: now)
        }
    }

    private var emptyState: some View {
        VStack(spacing: PKSpacing.s) {
            Text("아직 받은 알림이 없어요.")
                .font(PKTypography.row)
                .foregroundStyle(PKColor.textPrimary)
            Text("주차가 감지되면 여기에 남아요.")
                .font(PKTypography.supporting)
                .foregroundStyle(PKColor.textSecondary)
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.vertical, PKSpacing.xxl)
        .accessibilityElement(children: .combine)
    }

    /// Also runs when the user comes back from a confirmation, which is the moment a
    /// `확인이 필요해요` row becomes a `저장됨` one.
    private func reload() {
        candidates.refresh()
        now = Date()
        items = candidates.notificationHistory()
    }
}

/// One row: the fixed title, the time it arrived, and what became of it.
struct NotificationHistoryRow: View {
    let item: NotificationHistoryItem
    let now: Date

    var body: some View {
        HStack(spacing: PKSpacing.m) {
            VStack(alignment: .leading, spacing: PKSpacing.xs) {
                // Always the same words — this is the notification the user saw, and §7b
                // reprints it verbatim on every row rather than paraphrasing the outcome
                // into a headline the notification never had.
                HStack(alignment: .firstTextBaseline, spacing: PKSpacing.s) {
                    Text(CandidateNotificationCopy.title)
                        .font(PKTypography.row)
                        .foregroundStyle(PKColor.textPrimary)
                    Spacer(minLength: PKSpacing.s)
                    Text(ParkingDateText.dayAndTime(item.raisedAt, now: now))
                        .font(PKTypography.supporting)
                        .foregroundStyle(PKColor.textSecondary)
                }
                Text(item.detail)
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
            }

            if item.isTappable {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(PKColor.textSecondary)
                    .accessibilityHidden(true)
            }
        }
        .padding(PKSpacing.l)
        .frame(minHeight: PKSize.minimumTouchTarget)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(item.accessibilityText(now: now))
        .accessibilityAddTraits(item.isTappable ? [.isButton] : [])
    }
}
