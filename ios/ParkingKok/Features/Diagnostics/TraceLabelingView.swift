import Observation
import SwiftUI

/// Backing state for the trace labelling screen.
///
/// Reads straight from the store rather than observing anything: like the rest of the
/// diagnostics surface, this is an instrument read at a moment in time.
@MainActor
@Observable
final class TraceLabelingModel {
    private let store: (any TraceStoring)?

    private(set) var summaries: [TraceSessionSummary] = []
    /// The session the recorder may still append to. §9 allows splitting only a closed
    /// one, so the screen has to know which is which — and it is a property of the store,
    /// not of the §9 file schema, so it cannot come from a summary.
    private(set) var openSessionId: UUID?
    private(set) var failure: String?

    init(store: (any TraceStoring)? = DetectionRuntime.shared.traceStore) {
        self.store = store
    }

    var summary: TraceSummary {
        store?.summary() ?? .empty
    }

    func refresh() {
        summaries = store?.summaries() ?? []
        openSessionId = store?.openSessionId
    }

    func isOpen(_ id: UUID) -> Bool {
        openSessionId == id
    }

    func session(id: UUID) -> TraceSession? {
        store?.load(id: id)
    }

    func save(_ label: TraceLabel, for id: UUID) {
        guard let store else { return }
        do {
            try store.updateLabel(label, for: id)
            failure = nil
        } catch {
            failure = String(describing: error)
        }
        refresh()
    }

    /// Cuts a session in two at `index` (§9 "사람이 세션을 나눈다").
    ///
    /// Returns the reason it was refused, or `nil` on success. A refusal is a normal
    /// outcome here — §9 makes a split that would leave a one-event fragment invalid, and
    /// the person choosing the point has no way to know that until they choose it — so it
    /// is a value to show, not an error to swallow.
    func split(sessionId: UUID, atEventIndex index: Int) -> String? {
        guard let store else { return "저장소를 사용할 수 없습니다." }
        guard let parent = store.load(id: sessionId) else { return "세션을 찾을 수 없습니다." }
        do {
            let fragments = try TraceSessionSplit.split(parent, atEventIndex: index)
            try store.replace(sessionId, with: [fragments.leading, fragments.trailing])
            failure = nil
            refresh()
            return nil
        } catch let error as TraceSplitError {
            return Self.describe(error)
        } catch TraceStoreError.sessionIsOpen {
            return "기록 중인 세션은 나눌 수 없습니다. 닫힌 세션만 가능합니다."
        } catch {
            return String(describing: error)
        }
    }

    private static func describe(_ error: TraceSplitError) -> String {
        switch error {
        case .indexOutOfRange:
            "첫 이벤트 앞이나 마지막 이벤트 뒤로는 나눌 수 없습니다."
        case let .fragmentNotViable(leading, trailing):
            """
            나누면 \(leading)개 / \(trailing)개가 되어 한쪽이 이벤트 \
            \(TraceSessionBoundaryPolicy.minimumViableEventCount)개 미만입니다. \
            이벤트 1개짜리 세션은 fixture가 될 수 없어 거부합니다.
            """
        }
    }
}

/// Gaps are read as minutes on both screens that show them, so the rounding is agreed in
/// one place: the numbers a person compares against the 30-minute threshold have to be the
/// same numbers on the diagnostics summary and in the split list.
enum TraceGapFormat {
    static func minutes(_ millis: Int64) -> String {
        String(format: "%.1f분", Double(millis) / 60000)
    }
}

/// P0 instrumentation for docs/05 §9, not product UI.
///
/// §9 puts labelling on a human: "label은 기기가 알 수 없다. 사람이 앱에서 붙인다."
/// Deliberately the smallest thing that lets someone tag a ride right after taking it —
/// a trace labelled days later is a guess, and a guess is worse than `unknown` because
/// the converter would treat it as ground truth.
///
/// Carries no coordinate, for the same reason the rest of this screen does not:
/// screenshots end up in bug reports.
struct TraceLabelingView: View {
    @State private var model: TraceLabelingModel
    /// The session whose event list is open for cutting. A sheet rather than a pushed
    /// screen on purpose: a successful split replaces the session with two others, so the
    /// screen that showed it has to be gone and the list underneath has to be the thing
    /// the user lands back on.
    @State private var splitTarget: TraceSession?

    init(model: TraceLabelingModel = TraceLabelingModel()) {
        _model = State(initialValue: model)
    }

    var body: some View {
        List {
            summarySection
            sessionSection
        }
        .navigationTitle("이동 기록")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("새로고침") { model.refresh() }
            }
        }
        .onAppear { model.refresh() }
        .sheet(item: $splitTarget) { session in
            TraceSplitView(session: session) { index in
                model.split(sessionId: session.sessionId, atEventIndex: index)
            }
        }
    }

    private var summarySection: some View {
        Section("요약") {
            let summary = model.summary
            LabeledContent("세션", value: "\(summary.sessionCount)개")
            LabeledContent("이벤트", value: "\(summary.eventCount)개")
            LabeledContent("상한으로 버림", value: "\(summary.droppedSessionCount)개")
            LabeledContent("이벤트 1개 이하로 버림", value: "\(summary.nonViableDropCount)개")
            LabeledContent("라벨 없음", value: "\(summary.unlabeledSessionCount)개")
            LabeledContent("최대 gap", value: TraceGapFormat.minutes(summary.maxGapMillis))
            if let failure = model.failure {
                LabeledContent("라벨 저장 실패", value: failure).foregroundStyle(.red)
            }
        }
    }

    private var sessionSection: some View {
        Section {
            if model.summaries.isEmpty {
                Text("기록된 세션이 없습니다. 주행 세션이 열려야 기록이 시작됩니다.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(model.summaries) { summary in
                NavigationLink {
                    TraceLabelEditor(summary: summary) { model.save($0, for: summary.id) }
                } label: {
                    row(for: summary)
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    if !model.isOpen(summary.id) {
                        Button("나누기") { splitTarget = model.session(id: summary.id) }
                            .tint(.indigo)
                    }
                }
            }
        } header: {
            Text("세션")
        } footer: {
            Text("세션을 왼쪽으로 밀면 나눌 수 있습니다. 기록 중인 세션은 나눌 수 없습니다.")
        }
    }

    /// The gap is on the row because it is the reason to open the split screen at all: a
    /// session holding a 28-minute silence is one the boundary nearly cut and a person
    /// probably should.
    private func row(for summary: TraceSessionSummary) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(summary.startedAt.formatted(date: .abbreviated, time: .standard))
                .font(.callout)
            Text("\(Self.duration(summary.duration)) · \(summary.eventCount)개 이벤트")
                .font(.caption)
                .foregroundStyle(.secondary)
            if let gapStats = summary.gapStats, gapStats.maxGapMillis > 0 {
                Text("최대 gap \(TraceGapFormat.minutes(gapStats.maxGapMillis))")
                    .font(.caption)
                    .foregroundStyle(gapStats.gapsOver20MinCount > 0 ? Color.orange : Color.secondary)
            }
            HStack(spacing: 6) {
                Text(Self.describe(summary.label))
                    .foregroundStyle(summary.label.isLabeled ? Color.secondary : Color.orange)
                if summary.isSplitFragment {
                    Text("· 나눈 조각").foregroundStyle(.secondary)
                }
                if model.isOpen(summary.id) {
                    Text("· 기록 중").foregroundStyle(.blue)
                }
            }
            .font(.caption)
        }
    }

    private static func describe(_ label: TraceLabel) -> String {
        guard label.isLabeled else { return "라벨 없음" }
        let parked = label.parked.map { $0 ? "주차함" : "주차 안 함" } ?? "주차 미상"
        return "\(label.mode.rawValue) · \(parked)"
    }

    private static func duration(_ interval: TimeInterval) -> String {
        let seconds = Int(interval.rounded())
        return "\(seconds / 60)분 \(seconds % 60)초"
    }
}

/// The three fields §9 defines, and nothing else.
private struct TraceLabelEditor: View {
    let summary: TraceSessionSummary
    let onSave: (TraceLabel) -> Void

    @State private var mode: TraceMode
    @State private var parked: ParkedAnswer
    @State private var note: String

    init(summary: TraceSessionSummary, onSave: @escaping (TraceLabel) -> Void) {
        self.summary = summary
        self.onSave = onSave
        _mode = State(initialValue: summary.label.mode)
        _parked = State(initialValue: ParkedAnswer(summary.label.parked))
        _note = State(initialValue: summary.label.note ?? "")
    }

    /// `parked` is a tri-state in §9 (`true | false | null`), so the picker needs an
    /// explicit "unknown" rather than a toggle that would silently answer "no".
    private enum ParkedAnswer: String, CaseIterable, Identifiable {
        case unknown
        case yes
        case no

        init(_ value: Bool?) {
            switch value {
            case true: self = .yes
            case false: self = .no
            case nil: self = .unknown
            }
        }

        var id: String {
            rawValue
        }

        var value: Bool? {
            switch self {
            case .unknown: nil
            case .yes: true
            case .no: false
            }
        }

        var title: String {
            switch self {
            case .unknown: "미상"
            case .yes: "주차함"
            case .no: "주차 안 함"
            }
        }
    }

    var body: some View {
        Form {
            Section("세션") {
                LabeledContent("시작", value: summary.startedAt.formatted(date: .abbreviated, time: .standard))
                LabeledContent("종료", value: summary.endedAt.formatted(date: .omitted, time: .standard))
                LabeledContent("이벤트", value: "\(summary.eventCount)개")
            }
            Section("라벨") {
                Picker("이동 수단", selection: $mode) {
                    ForEach(TraceMode.allCases) { mode in
                        Text(mode.rawValue).tag(mode)
                    }
                }
                Picker("주차 여부", selection: $parked) {
                    ForEach(ParkedAnswer.allCases) { answer in
                        Text(answer.title).tag(answer)
                    }
                }
                TextField("메모 (예: 지하 3층, 진입 후 GPS 소실)", text: $note, axis: .vertical)
            }
            Section {
                Button("저장") {
                    let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
                    onSave(TraceLabel(mode: mode, parked: parked.value, note: trimmed.isEmpty ? nil : trimmed))
                }
            }
        }
        .navigationTitle("라벨")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Picks the cut point for docs/05 §9's "사람이 세션을 나눈다".
///
/// ### Why the event list, and not a time picker
/// The thing the person is looking for is a boundary between sitting still and travelling,
/// and it is invisible in a clock. In the September 2026 subway trace the office wait and
/// the ride were separated by nothing but the meaning of the events on either side: a
/// `stationary_enter` at 17:37 followed 28.4 minutes later by a `walking_enter`, with the
/// `vehicle_enter` that starts the actual ride another 18 minutes after that. A picker
/// showing times alone would have made that boundary unfindable.
///
/// So every event is listed with its type, and the silence before it is spelled out above
/// it — the long silences are what the eye is scanning for, and they are what the split
/// exists to act on.
private struct TraceSplitView: View {
    let session: TraceSession
    /// Returns the reason the split was refused, or `nil` when it happened.
    let onSplit: (Int) -> String?

    @Environment(\.dismiss) private var dismiss
    @State private var pendingIndex: Int?
    @State private var failure: String?

    var body: some View {
        NavigationStack {
            List {
                if let failure {
                    Section {
                        Text(failure).font(.footnote).foregroundStyle(.red)
                    }
                }
                Section {
                    ForEach(Array(session.events.enumerated()), id: \.offset) { offset, event in
                        eventRow(offset: offset, event: event)
                    }
                } header: {
                    Text("이벤트 \(session.events.count)개")
                } footer: {
                    Text("고른 이벤트가 두 번째 세션의 첫 이벤트가 됩니다. 조각마다 라벨을 따로 붙입니다.")
                }
            }
            .navigationTitle("세션 나누기")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("취소") { dismiss() }
                }
            }
            .confirmationDialog(
                "이 이벤트부터 새 세션으로 나눕니다",
                isPresented: Binding(get: { pendingIndex != nil }, set: {
                    if !$0 {
                        pendingIndex = nil
                    }
                }),
                titleVisibility: .visible
            ) {
                // The parent is replaced, and its label is not carried over, so the tap is
                // worth one confirmation even on a diagnostics screen.
                Button("나누기", role: .destructive) { commit() }
                Button("취소", role: .cancel) { pendingIndex = nil }
            } message: {
                Text("원본 세션은 두 조각으로 대체되고, 라벨은 다시 붙여야 합니다.")
            }
        }
    }

    /// The first event cannot be a cut point — the split has to leave events on both
    /// sides — so it is shown without an action rather than hidden, because hiding it
    /// would make the list disagree with the trace it is showing.
    @ViewBuilder
    private func eventRow(offset: Int, event: TraceEvent) -> some View {
        if offset == 0 {
            eventLabel(offset: offset, event: event).foregroundStyle(.secondary)
        } else {
            Button {
                failure = nil
                pendingIndex = offset
            } label: {
                eventLabel(offset: offset, event: event)
            }
            .buttonStyle(.plain)
        }
    }

    private func eventLabel(offset: Int, event: TraceEvent) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if offset > 0 {
                let gap = event.atMillis - session.events[offset - 1].atMillis
                Text("↕ \(TraceGapFormat.minutes(gap)) 침묵")
                    .font(.caption2)
                    .foregroundStyle(gap > TraceGapStats.tenMinutesMillis ? Color.orange : Color.secondary)
            }
            HStack {
                Text(event.date.formatted(date: .omitted, time: .standard))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                Text(event.type.rawValue).font(.callout)
            }
            if let detail = Self.detail(of: event) {
                Text(detail).font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    /// Whatever the event carries beyond its type, which is what tells a walk from a ride
    /// when the type alone is ambiguous. Never a coordinate — there is none to show.
    private static func detail(of event: TraceEvent) -> String? {
        var parts: [String] = []
        if let confidence = event.confidence {
            parts.append("확신도 \(confidence.rawValue)")
        }
        if let accuracy = event.accuracy {
            parts.append(String(format: "정확도 %.0fm", accuracy))
        }
        if let speed = event.speed {
            parts.append(String(format: "%.1fm/s", speed))
        }
        if let distance = event.distanceFromPreviousM {
            parts.append(String(format: "이동 %.0fm", distance))
        }
        if let from = event.fromBucket, let to = event.toBucket {
            parts.append("\(from.rawValue) → \(to.rawValue)")
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func commit() {
        guard let index = pendingIndex else { return }
        pendingIndex = nil
        if let reason = onSplit(index) {
            failure = reason
        } else {
            dismiss()
        }
    }
}
