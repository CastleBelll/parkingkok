import SwiftUI

/// P0 instrumentation, not product UI.
///
/// Shows what the low-power wake and rehydration path actually did on device, which is
/// the only way to verify a code path that runs while the app is dead
/// (docs/04_IOS_IMPLEMENTATION.md §3, §6).
///
/// Coordinates are absent here as well as from the logs: screenshots of this screen end
/// up in bug reports and review material, so the safer default wins. Accuracy and
/// timestamps prove a fix was received without revealing where.
struct DiagnosticsView: View {
    private let appInfo: AppInfo
    @State private var model: DiagnosticsModel

    init(appInfo: AppInfo = .current, model: DiagnosticsModel = DiagnosticsModel()) {
        self.appInfo = appInfo
        _model = State(initialValue: model)
    }

    var body: some View {
        List {
            buildSection
            permissionSection
            checkpointSection
            motionSection
        }
        .navigationTitle("감지 진단")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("새로고침") {
                    Task { await model.refresh() }
                }
            }
        }
        .task { await model.refresh() }
    }

    private var buildSection: some View {
        Section("빌드") {
            LabeledContent("앱", value: appInfo.displayName)
            LabeledContent("버전", value: appInfo.versionSummary)
            LabeledContent("실행 사유", value: model.snapshot.launchReason.rawValue)
            if let rehydratedAt = model.snapshot.rehydratedAt {
                LabeledContent("복원 시각", value: Self.time(rehydratedAt))
            }
        }
    }

    private var permissionSection: some View {
        Section("권한") {
            Toggle("Smart Detection", isOn: Binding(
                get: { model.isSmartDetectionEnabled },
                set: { enabled in Task { await model.setSmartDetection(enabled) } }
            ))
            LabeledContent("위치", value: model.locationAuthorization.rawValue)
            LabeledContent("모션", value: model.motionAuthorization.rawValue)
            LabeledContent("모션 히스토리 지원", value: model.isMotionHistoryAvailable ? "가능" : "불가")
            LabeledContent("significant-change 모니터링", value: model.isMonitoring ? "ON" : "OFF")

            if let request = model.pendingPermissionRequest {
                Button("위치 권한 요청 (\(request == .always ? "Always" : "When In Use"))") {
                    Task { await model.requestLocationPermission() }
                }
            } else if model.locationAuthorization.requiresSettingsChange {
                // A denial is not an app failure; manual parking still works.
                Text("설정에서만 변경 가능합니다. 수동 주차 기록은 계속 사용할 수 있습니다.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            if model.motionAuthorization == .notDetermined {
                Button("모션 권한 요청") {
                    Task { await model.requestMotionPermission() }
                }
            }
        }
    }

    private var checkpointSection: some View {
        Section("체크포인트") {
            LabeledContent("상태", value: model.snapshot.checkpointDescription)
                .foregroundStyle(model.snapshot.isCheckpointFailed ? Color.red : Color.primary)

            if let checkpoint = model.snapshot.displayCheckpoint {
                LabeledContent("상태 진입", value: Self.time(checkpoint.stateEnteredAt))
                LabeledContent("마지막 automotive", value: Self.optionalTime(checkpoint.lastAutomotiveAt))
                LabeledContent("마지막 위치 시각", value: Self.optionalTime(checkpoint.lastLocationAt))
                LabeledContent("이동 거리 추정", value: "\(Int(checkpoint.travelDistanceEstimate)) m")
                LabeledContent("candidateId", value: checkpoint.candidateId?.uuidString ?? "없음")
                // Always "없음" in M0A-1: selection is M0A-2.
                LabeledContent(
                    "lastReliableLocation",
                    value: checkpoint.lastReliableLocation.map { "정확도 \(Int($0.horizontalAccuracy))m" } ?? "없음 (M0A-2)"
                )
            }

            if let age = model.snapshot.checkpointAge {
                LabeledContent("체크포인트 경과", value: Self.duration(age))
            }
            if model.snapshot.isBeyondMotionRetention {
                Text("모션 보존 기간(7일)을 넘긴 체크포인트입니다.")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
            LabeledContent("significant-change 수신", value: "\(model.snapshot.significantChangeCount)회")
            if let accuracy = model.snapshot.lastLocationAccuracy {
                LabeledContent("마지막 수평 정확도", value: "\(Int(accuracy)) m")
            }
            if let failure = model.storeSetupFailure {
                LabeledContent("저장소 초기화 실패", value: failure).foregroundStyle(.red)
            }
            if let failure = model.snapshot.lastPersistError {
                LabeledContent("저장 실패", value: failure).foregroundStyle(.red)
            }
            if let failure = model.snapshot.locationFailure {
                LabeledContent("위치 오류", value: failure).foregroundStyle(.orange)
            }
        }
    }

    private var motionSection: some View {
        Section("모션 히스토리") {
            if let window = model.snapshot.motionWindow {
                LabeledContent("조회 구간", value: "\(Self.time(window.start)) → \(Self.time(window.end))")
            }
            LabeledContent("샘플 수", value: "\(model.snapshot.motionSamples.count)")
            if let failure = model.snapshot.motionFailure {
                LabeledContent("조회 실패", value: failure).foregroundStyle(.orange)
            }
            ForEach(Array(Self.recentSamples(model.snapshot.motionSamples).enumerated()), id: \.offset) { _, sample in
                LabeledContent(
                    Self.time(sample.timestamp),
                    value: "\(sample.activityFlagsDescription) · \(sample.confidence.rawValue)"
                )
                .font(.caption.monospaced())
            }
        }
    }

    /// Newest first, capped — a 30-minute window can return hundreds of slices.
    private static func recentSamples(_ samples: [MotionSample]) -> [MotionSample] {
        Array(samples.reversed().prefix(20))
    }

    private static func time(_ date: Date) -> String {
        date.formatted(date: .omitted, time: .standard)
    }

    private static func optionalTime(_ date: Date?) -> String {
        date.map(time) ?? "없음"
    }

    private static func duration(_ interval: TimeInterval) -> String {
        let seconds = Int(interval.rounded())
        return "\(seconds / 60)분 \(seconds % 60)초"
    }
}

#Preview {
    NavigationStack {
        DiagnosticsView()
    }
}
