import PhotosUI
import SwiftUI
import UIKit

/// One parking in full (`design-references/03-parking-detail.png`).
///
/// Order follows the mock: map, summary, facts, the two secondary calls to action
/// (`길찾기` · `사진 보기`), the photo panel, then `주차 종료`. docs/19 §3 fixes those
/// three as the primary CTAs of this screen.
///
/// Two things the mock shows that this cannot honestly reproduce:
/// - Its map pin claims a spot. FR-008 forbids wording or imagery that asserts the exact
///   car position, so `ParkingMapCard` draws the `horizontalAccuracy` circle under the
///   pin and captions it `마지막으로 확인된 위치`.
/// - Its `길찾기` and `사진 보기` are always live. A record saved without location
///   permission (FR-001) has nothing to navigate to, so `길찾기` is disabled and says
///   why rather than opening a map of nowhere.
struct ParkingDetailView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @Bindable private var model: ParkingModel
    private let sessionID: UUID

    @Environment(\.dismiss) private var dismiss
    @State private var isEditing = false
    @State private var isConfirmingDelete = false
    @State private var displayNow = Date()

    @State private var photoPhase: ParkingPhotoPhase = .empty
    @State private var isChoosingPhotoSource = false
    @State private var isPickingFromLibrary = false
    @State private var isCapturingPhoto = false
    @State private var isViewingPhoto = false
    @State private var libraryItem: PhotosPickerItem?

    init(model: ParkingModel, sessionID: UUID) {
        self.model = model
        self.sessionID = sessionID
    }

    var body: some View {
        PKScreen {
            if let session {
                Group {
                    if let point = ParkingMapPoint(session) {
                        ParkingMapCard(point: point, floorText: session.floor?.displayText)
                    } else {
                        ParkingMapUnavailableCard()
                    }
                }
                .pkEntrance(0)
                summaryCard(session)
                    .pkEntrance(1)
                factsCard(session)
                    .pkEntrance(2)
                if let failure = model.failure {
                    PKNoticeCard(text: failure)
                        .pkEntrance(3)
                }
                secondaryActions(session)
                    .pkEntrance(3)
                ParkingPhotoCard(
                    phase: photoPhase,
                    now: displayNow,
                    onAdd: beginAddingPhoto,
                    onOpen: { isViewingPhoto = true }
                )
                .pkEntrance(4)
                primaryActions(session)
                    .pkEntrance(4)
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
        // Storage work belongs in a task, never in `body` (docs/16 §5). Keyed on the
        // stored path so attaching or removing a photo reloads exactly once.
        .task(id: session?.photoRelativePath) { await loadPhoto() }
        .sheet(isPresented: $isEditing) {
            if let session {
                ManualParkingSheet(model: model, editing: session)
            }
        }
        .confirmationDialog("이 주차 기록을 삭제할까요?", isPresented: $isConfirmingDelete, titleVisibility: .visible) {
            Button("삭제", role: .destructive) {
                Task {
                    await model.delete(id: sessionID)
                    dismiss()
                }
            }
            Button("취소", role: .cancel) {}
        } message: {
            Text("삭제한 기록은 되돌릴 수 없어요. 저장된 사진도 함께 삭제돼요.")
        }
        .confirmationDialog("사진 추가", isPresented: $isChoosingPhotoSource, titleVisibility: .visible) {
            ForEach(ParkingPhotoSource.available) { source in
                Button(source.title) { present(source) }
            }
            Button("취소", role: .cancel) {}
        }
        // The system picker runs out of process, so no photo-library permission is
        // requested and none is declared in Info.plist.
        .photosPicker(
            isPresented: $isPickingFromLibrary,
            selection: $libraryItem,
            matching: .images,
            photoLibrary: .shared()
        )
        .fullScreenCover(isPresented: $isCapturingPhoto) {
            CameraPhotoPicker(
                onPicked: { data in
                    isCapturingPhoto = false
                    Task { await model.attachPhoto(data, to: sessionID) }
                },
                onCancel: { isCapturingPhoto = false }
            )
            .ignoresSafeArea()
        }
        .fullScreenCover(isPresented: $isViewingPhoto) {
            if case let .loaded(image, _) = photoPhase {
                ParkingPhotoViewer(image: image) {
                    Task { await model.removePhoto(from: sessionID) }
                }
            }
        }
        .onChange(of: libraryItem) { _, item in
            guard let item else { return }
            Task { await attachPickedLibraryItem(item) }
        }
    }

    /// Read through the model so an edit made in the sheet is reflected here.
    private var session: ParkingSession? {
        model.session(id: sessionID)
    }

    // ── Cards ───────────────────────────────────────────────────────────────

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
                    title: ParkingMapPoint.label,
                    value: locationText(session)
                )
            }
            .padding(.vertical, PKSpacing.xs)
        }
    }

    /// FR-008 forbids wording that implies the exact car position, and the accuracy is
    /// the honest version of it. No location at all is the FR-001 case and says so.
    private func locationText(_ session: ParkingSession) -> String {
        ParkingMapPoint(session)?.accuracyText ?? "저장 안 됨"
    }

    private var divider: some View {
        Rectangle()
            .fill(PKColor.divider)
            .frame(height: PKSize.hairline)
            .padding(.leading, PKSpacing.xxl + PKSpacing.l)
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    /// The mock's pair: `길찾기` and `사진 보기`, side by side under the facts.
    @ViewBuilder
    private func secondaryActions(_ session: ParkingSession) -> some View {
        let point = ParkingMapPoint(session)
        VStack(spacing: PKSpacing.s) {
            HStack(spacing: PKSpacing.m) {
                Button {
                    if let point {
                        ParkingDirections.open(point)
                    }
                } label: {
                    Label("길찾기", systemImage: "location.fill")
                }
                .buttonStyle(PKSoftButtonStyle())
                .disabled(point == nil)
                .opacity(point == nil ? 0.4 : 1)
                .accessibilityHint("지도 앱에서 걸어가는 길을 엽니다")

                Button(action: openOrAddPhoto) {
                    Label(photoActionTitle, systemImage: "camera.fill")
                }
                .buttonStyle(PKSoftButtonStyle())
            }
            if point == nil {
                // Same reasoning as the home screen's floor-stepper hint: a dimmed button
                // with no explanation reads as a bug.
                Text("위치 없이 저장된 기록이라 길찾기를 쓸 수 없어요.")
                    .font(PKTypography.caption)
                    .foregroundStyle(PKColor.textSecondary)
            }
        }
    }

    private var photoActionTitle: String {
        if case .loaded = photoPhase {
            return "사진 보기"
        }
        return "사진 추가"
    }

    @ViewBuilder
    private func primaryActions(_ session: ParkingSession) -> some View {
        if session.isActive {
            PKPrimaryActionButton(
                title: "주차 종료",
                subtitle: "주차를 종료하고 기록을 저장합니다",
                systemImage: "flag.checkered"
            ) {
                pkWithAnimation(PKMotion.sessionChange, reduceMotion: reduceMotion) {
                    model.endActiveParking()
                }
                dismiss()
            }
        }
        Button("기록 삭제", role: .destructive) { isConfirmingDelete = true }
            .font(PKTypography.row)
            .tint(PKColor.danger)
            .frame(maxWidth: .infinity, minHeight: PKSize.minimumTouchTarget)
    }

    // ── Photo ───────────────────────────────────────────────────────────────

    private func openOrAddPhoto() {
        if case .loaded = photoPhase {
            isViewingPhoto = true
        } else {
            beginAddingPhoto()
        }
    }

    /// Skips the chooser when there is nothing to choose — a device with no camera, or
    /// one where camera access is off, leaves only the library.
    private func beginAddingPhoto() {
        let sources = ParkingPhotoSource.available
        if sources.count == 1, let only = sources.first {
            present(only)
        } else {
            isChoosingPhotoSource = true
        }
    }

    private func present(_ source: ParkingPhotoSource) {
        switch source {
        case .camera: isCapturingPhoto = true
        case .library: isPickingFromLibrary = true
        }
    }

    private func attachPickedLibraryItem(_ item: PhotosPickerItem) async {
        defer { libraryItem = nil }
        // `Data` rather than `Image`: the original bytes go straight to ImageIO, which
        // downsamples without ever decoding the full-resolution image (docs/11 §12).
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            photoPhase = .empty
            return
        }
        await model.attachPhoto(data, to: sessionID)
    }

    private func loadPhoto() async {
        guard let session, session.photoRelativePath != nil else {
            photoPhase = .empty
            return
        }
        photoPhase = .loading
        guard let photo = await model.photo(for: session), let image = UIImage(data: photo.data) else {
            photoPhase = .missing
            return
        }
        photoPhase = .loaded(image: Image(uiImage: image), savedAt: photo.savedAt)
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
