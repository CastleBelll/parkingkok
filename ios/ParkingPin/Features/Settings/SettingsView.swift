import AuthenticationServices
import SwiftUI

/// Settings (`design-references/05-settings.png`).
///
/// Section order is docs/19's: 자동 감지 → 알림 → 권한 → Plus → 데이터 → 개인정보, with a
/// 개발자 section at the end that is not in the mock.
///
/// Features that do not exist yet are shown disabled and labelled `준비 중` rather than
/// hidden or faked. A switch that does nothing would be worse than an honest gap — and
/// hiding them would lose the section order the mock establishes.
struct SettingsView: View {
    private let appInfo: AppInfo
    private let parkingModel: ParkingModel?
    @Binding private var path: [AppRoute]

    @State private var model = SettingsModel()
    @State private var isConfirmingDataDeletion = false

    init(appInfo: AppInfo, model: ParkingModel?, path: Binding<[AppRoute]>) {
        self.appInfo = appInfo
        parkingModel = model
        _path = path
    }

    /// docs/10 §7b "Where settings went": the 알림 section stays exactly here, which is
    /// "where the rest of the switches live". The bell no longer scrolls anyone to it —
    /// it opens the notification history instead — so there is no focus to honour and no
    /// `ScrollViewReader` wrapped around the list for one caller that no longer exists.
    var body: some View {
        List {
            accountSection
            detectionSection
            notificationSection
            permissionSection
            plusSection
            dataSection
            privacySection
            developerSection
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(PKColor.background)
        .navigationTitle("설정")
        .navigationBarTitleDisplayMode(.large)
        .task { await model.refresh() }
        .confirmationDialog(
            "이 기기의 주차 데이터를 모두 삭제할까요?",
            isPresented: $isConfirmingDataDeletion,
            titleVisibility: .visible
        ) {
            Button("전체 삭제", role: .destructive) {
                Task { await parkingModel?.deleteAllLocalData() }
            }
            Button("취소", role: .cancel) {}
        } message: {
            Text("진행 중인 주차와 모든 기록이 사라져요. 되돌릴 수 없어요.")
        }
    }

    /// The mock's banner — no sign-up, nothing leaves the device — and, under it, the one
    /// row that changes that.
    ///
    /// docs/07 §13a: signing in **links** a provider to the anonymous uid this device
    /// already has. It is not a backup and not a restore, and the copy never says 백업 or
    /// 복원, because a user who reads either will expect their records on the next phone
    /// and there is no sync to give them one. So this stays a quiet row rather than a
    /// sign-in wall: the app has never needed an account and still does not.
    private var accountSection: some View {
        Section {
            HStack(spacing: PKSpacing.l) {
                PKIconChip(model.isSignedIn ? "person.crop.circle.badge.checkmark" : "iphone")
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.isSignedIn ? "Apple 계정 연결됨" : "회원가입 없이 사용 중")
                        .font(PKTypography.row)
                        .foregroundStyle(PKColor.textPrimary)
                    Text(
                        model.isSignedIn
                            ? "주차 기록은 계속 이 기기에만 저장돼요"
                            : "모든 주차 데이터는 이 기기에만 저장됩니다"
                    )
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
                }
            }
            .padding(.vertical, PKSpacing.xs)
            .accessibilityElement(children: .combine)

            if model.isSignedIn {
                Button("연결 해제") {
                    Task { await model.signOut() }
                }
                .font(PKTypography.row)
                .foregroundStyle(PKColor.textPrimary)
                .disabled(model.isAccountBusy)
            } else {
                VStack(alignment: .leading, spacing: PKSpacing.s) {
                    // Apple's own button, at Apple's own sizing. A custom-styled one is a
                    // review rejection, and this is the rare case where the platform's
                    // control outranks the design harness.
                    SignInWithAppleButton(
                        .signIn,
                        onRequest: { model.prepareAppleRequest($0) },
                        onCompletion: { result in
                            Task { await model.completeAppleSignIn(result) }
                        }
                    )
                    .signInWithAppleButtonStyle(.black)
                    .frame(height: 44)
                    .clipShape(RoundedRectangle(cornerRadius: PKRadius.button, style: .continuous))
                    .disabled(model.isAccountBusy)

                    Text("나중에 추가될 기능을 위해 계정을 연결해 둬요")
                        .font(PKTypography.supporting)
                        .foregroundStyle(PKColor.textSecondary)
                }
                .padding(.vertical, PKSpacing.xs)
            }

            if let message = model.accountMessage {
                Text(message)
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textSecondary)
                    .padding(.vertical, PKSpacing.xs)
            }
        }
        .listRowBackground(PKColor.surface)
    }

    /// The analytics opt-in sits here rather than under 개인정보 because it is the setting
    /// that improves detection, and docs/10 §8's rule is that a switch is explained by what
    /// it is for.
    ///
    /// **The footer is docs/09 §13's sentence, verbatim.** §13 forbids an absolute
    /// "no data leaves the device" claim — Firebase and ad SDK metadata does leave — so
    /// this says the specific true thing instead of the sweeping false one.
    private var detectionSection: some View {
        Section {
            Toggle(isOn: Binding(
                get: { model.isSmartDetectionEnabled },
                set: { enabled in Task { await model.setSmartDetection(enabled) } }
            )) {
                SettingsLabel(title: "자동 주차 감지", subtitle: "주차한 순간을 앱이 먼저 알아차려요")
            }
            // FR-010. Not built: the departure detector does not exist yet.
            SettingsPlaceholderRow(
                title: "주차 종료 자동 감지",
                subtitle: "출차하면 주차를 자동으로 종료해요"
            )
            Toggle(isOn: Binding(
                get: { model.isAnalyticsConsentGranted },
                set: { granted in Task { await model.setAnalyticsConsent(granted) } }
            )) {
                SettingsLabel(
                    title: "사용 통계 공유",
                    subtitle: "감지 정확도를 개선하는 익명 통계를 보내요"
                )
            }
        } header: {
            Text("자동 감지")
        } footer: {
            Text("주차 위치 좌표와 주차 사진은 주차핀 서버에 저장하지 않습니다.")
        }
        .listRowBackground(PKColor.surface)
    }

    private var notificationSection: some View {
        Section("알림") {
            SettingsStatusRow(
                title: "알림 권한",
                subtitle: "주차가 감지되면 알려드려요",
                status: model.notificationStatusText
            ) {
                if model.notificationAuthorization == "notDetermined" {
                    Task { await model.requestNotificationPermission() }
                } else {
                    model.openSystemSettings()
                }
            }
            // FR-011. No widget target in the build yet.
            SettingsPlaceholderRow(title: "위젯", subtitle: "홈 화면에서 층을 바로 확인해요")
        }
        .listRowBackground(PKColor.surface)
    }

    /// docs/10 §8: one purpose per permission, and a denial is never presented as an app
    /// failure — manual parking keeps working either way.
    private var permissionSection: some View {
        Section {
            SettingsStatusRow(
                title: "위치 권한",
                subtitle: "주차한 위치를 기록하기 위해 필요해요",
                status: model.locationStatusText,
                action: model.openSystemSettings
            )
            SettingsStatusRow(
                title: "동작 및 피트니스",
                subtitle: "차량 이동과 도보를 구분하기 위해 필요해요",
                status: model.motionStatusText,
                action: model.openSystemSettings
            )
        } header: {
            Text("권한")
        } footer: {
            Text("권한을 모두 거부해도 주차를 직접 저장하고 확인할 수 있어요.")
        }
        .listRowBackground(PKColor.surface)
    }

    /// FR-012. No StoreKit product exists, and docs/10 §9 forbids a fake badge — so this
    /// says what it is instead of showing a price nobody can pay.
    private var plusSection: some View {
        Section("주차핀 Plus") {
            SettingsPlaceholderRow(title: "Plus 구독", subtitle: "기록 무제한과 위젯 +/- 를 준비 중이에요")
        }
        .listRowBackground(PKColor.surface)
    }

    private var dataSection: some View {
        Section("데이터") {
            // FR-011 export is a Plus feature with no subscription behind it yet.
            SettingsPlaceholderRow(title: "주차 기록 내보내기", subtitle: "기록을 CSV 파일로 저장해요")
            Button(role: .destructive) {
                isConfirmingDataDeletion = true
            } label: {
                SettingsLabel(title: "이 기기의 주차 데이터 삭제", subtitle: "기록과 진행 중인 주차를 모두 지워요")
            }
            .disabled(parkingModel == nil)
        }
        .listRowBackground(PKColor.surface)
    }

    private var privacySection: some View {
        Section {
            HStack(spacing: PKSpacing.s) {
                Image(systemName: "lock.fill")
                    .foregroundStyle(PKColor.textSecondary)
                    .accessibilityHidden(true)
                Text("주차핀은 위치 정보를 서버로 전송하지 않습니다.")
                    .font(PKTypography.supporting)
                    .foregroundStyle(PKColor.textPrimary)
            }
            .padding(.vertical, PKSpacing.xs)
            .accessibilityElement(children: .combine)
        } header: {
            Text("개인정보")
        } footer: {
            Text("좌표·사진·메모는 이 기기에만 저장되고, 계정이나 서버에 올라가지 않습니다.")
        }
        .listRowBackground(PKColor.surface)
    }

    /// Where the P0 diagnostics readout moved to. It is still in every build because the
    /// field-test checklists in `ios/README.md` have no other way to read the counters.
    private var developerSection: some View {
        Section {
            Button { path.append(.diagnostics) } label: {
                HStack {
                    SettingsLabel(title: "감지 진단", subtitle: "저전력 깨우기와 복원 상태를 확인해요")
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(PKColor.textSecondary)
                        .accessibilityHidden(true)
                }
            }
            .tint(PKColor.textPrimary)
            LabeledContent("버전", value: appInfo.versionSummary)
                .font(PKTypography.supporting)
            #if PK_DEV
                // What the Auth SDK on *this device* believes, verbatim, so it can be
                // compared with `firebase auth:export`. The two disagreed on 2026-09-21 —
                // the screen said linked and the project said anonymous — and with no way
                // to read a device log without root there was nothing to check it against.
                // A uid is not a coordinate and this is compiled out of STAGING and PROD.
                LabeledContent("계정", value: model.accountDebugSummary)
                    .font(PKTypography.supporting)
                    .textSelection(.enabled)
            #endif
        } header: {
            Text("개발자")
        }
        .listRowBackground(PKColor.surface)
    }
}

private struct SettingsLabel: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(PKTypography.row)
            Text(subtitle)
                .font(PKTypography.supporting)
                .foregroundStyle(PKColor.textSecondary)
        }
        .multilineTextAlignment(.leading)
    }
}

/// A permission row: what it is for, what the system currently says, and a way to change
/// it. The status is a word, never only a colour (docs/01 §8).
private struct SettingsStatusRow: View {
    let title: String
    let subtitle: String
    let status: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                SettingsLabel(title: title, subtitle: subtitle)
                Spacer(minLength: PKSpacing.s)
                PKBadge(status, tone: tone)
            }
        }
        .tint(PKColor.textPrimary)
        .accessibilityElement(children: .combine)
        .accessibilityValue(status)
    }

    private var tone: PKBadge.Tone {
        switch status {
        case "항상 허용", "허용됨": .positive
        case "거부됨", "제한됨": .muted
        default: .info
        }
    }
}

/// A feature the app does not have yet. Disabled and named as such — docs/19's quality
/// bar is that the UI does not overstate what is there.
private struct SettingsPlaceholderRow: View {
    let title: String
    let subtitle: String

    var body: some View {
        HStack {
            SettingsLabel(title: title, subtitle: subtitle)
            Spacer(minLength: PKSpacing.s)
            PKBadge("준비 중", tone: .muted)
        }
        .foregroundStyle(PKColor.textSecondary)
        .accessibilityElement(children: .combine)
        .accessibilityValue("준비 중, 아직 사용할 수 없습니다")
    }
}
