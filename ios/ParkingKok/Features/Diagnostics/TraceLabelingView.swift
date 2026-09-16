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
    private(set) var failure: String?

    init(store: (any TraceStoring)? = DetectionRuntime.shared.traceStore) {
        self.store = store
    }

    var summary: TraceSummary {
        store?.summary() ?? .empty
    }

    func refresh() {
        summaries = store?.summaries() ?? []
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
    }

    private var summarySection: some View {
        Section("요약") {
            let summary = model.summary
            LabeledContent("세션", value: "\(summary.sessionCount)개")
            LabeledContent("이벤트", value: "\(summary.eventCount)개")
            LabeledContent("상한으로 버림", value: "\(summary.droppedSessionCount)개")
            LabeledContent("라벨 없음", value: "\(summary.unlabeledSessionCount)개")
            if let failure = model.failure {
                LabeledContent("라벨 저장 실패", value: failure).foregroundStyle(.red)
            }
        }
    }

    private var sessionSection: some View {
        Section("세션") {
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
            }
        }
    }

    private func row(for summary: TraceSessionSummary) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(summary.startedAt.formatted(date: .abbreviated, time: .standard))
                .font(.callout)
            Text("\(Self.duration(summary.duration)) · \(summary.eventCount)개 이벤트")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(Self.describe(summary.label))
                .font(.caption)
                .foregroundStyle(summary.label.isLabeled ? Color.secondary : Color.orange)
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
