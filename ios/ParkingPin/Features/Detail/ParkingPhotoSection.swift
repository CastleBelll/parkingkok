import SwiftUI

/// Where the detail screen's photo panel is in its life (FR-007).
///
/// `missing` is its own case rather than collapsing into `empty`: a record whose file
/// vanished — an iCloud restore mid-download, storage cleared, a cleanup that ran wide —
/// should say so and offer a new photo, not pretend one was never taken.
enum ParkingPhotoPhase: Equatable {
    case empty
    case loading
    case loaded(image: Image, savedAt: Date?)
    case missing
}

/// The `주차 사진` panel from `design-references/03-parking-detail.png`.
///
/// Presentation only: the bytes are loaded by the screen and handed in. docs/16 §5
/// forbids storage work inside `body`, and a `View` that reads a file while rendering is
/// exactly that.
struct ParkingPhotoCard: View {
    /// The mock's photo sits in a wide, shallow band under the facts. Tall enough to
    /// recognise a pillar, short enough that 주차 종료 stays reachable without scrolling.
    static let imageHeight: CGFloat = 200

    private let phase: ParkingPhotoPhase
    private let now: Date
    private let onAdd: () -> Void
    private let onOpen: () -> Void

    init(phase: ParkingPhotoPhase, now: Date, onAdd: @escaping () -> Void, onOpen: @escaping () -> Void) {
        self.phase = phase
        self.now = now
        self.onAdd = onAdd
        self.onOpen = onOpen
    }

    var body: some View {
        PKCard {
            VStack(alignment: .leading, spacing: PKSpacing.m) {
                header
                content
            }
            .padding(PKSpacing.l)
        }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("주차 사진")
                .font(PKTypography.sectionTitle)
                .foregroundStyle(PKColor.textPrimary)
            Spacer(minLength: PKSpacing.s)
            if case let .loaded(_, savedAt) = phase, let savedAt {
                Text("\(ParkingDateText.dayAndTime(savedAt, now: now))에 저장됨")
                    .font(PKTypography.caption)
                    .foregroundStyle(PKColor.textSecondary)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .empty:
            placeholder(
                icon: "camera",
                text: "아직 사진이 없어요.\n기둥 번호나 주변을 찍어두면 찾기 쉬워요.",
                actionTitle: "사진 추가",
                action: onAdd
            )
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, minHeight: Self.imageHeight)
                .accessibilityLabel("사진을 불러오는 중")
        case let .loaded(image, _):
            Button(action: onOpen) {
                image
                    .resizable()
                    .scaledToFill()
                    .frame(maxWidth: .infinity)
                    .frame(height: Self.imageHeight)
                    .clipShape(.rect(cornerRadius: PKRadius.row))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("주차 사진 크게 보기")
        case .missing:
            placeholder(
                icon: "exclamationmark.triangle",
                text: "저장된 사진을 찾지 못했어요.\n다시 추가할 수 있어요.",
                actionTitle: "사진 다시 추가",
                action: onAdd
            )
        }
    }

    private func placeholder(
        icon: String,
        text: String,
        actionTitle: String,
        action: @escaping () -> Void
    ) -> some View {
        VStack(spacing: PKSpacing.m) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(PKColor.textSecondary)
                .accessibilityHidden(true)
            Text(text)
                .font(PKTypography.supporting)
                .foregroundStyle(PKColor.textSecondary)
                .multilineTextAlignment(.center)
            Button(actionTitle, action: action)
                .buttonStyle(PKSoftButtonStyle())
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, PKSpacing.l)
    }
}

/// Full-screen photo, opened from the card or from `사진 보기`.
///
/// No pinch-zoom: the stored image is capped at 1600px on its long edge (FR-007), which
/// is already close to 1:1 on the widest supported display, so a zoom gesture would
/// magnify compression artefacts rather than reveal a pillar number.
struct ParkingPhotoViewer: View {
    private let image: Image
    private let onDelete: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var isConfirmingDelete = false

    init(image: Image, onDelete: @escaping () -> Void) {
        self.image = image
        self.onDelete = onDelete
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                image
                    .resizable()
                    .scaledToFit()
                    .accessibilityLabel("주차 사진")
            }
            .navigationTitle("주차 사진")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("닫기") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("삭제", role: .destructive) { isConfirmingDelete = true }
                        .tint(PKColor.danger)
                }
            }
            .confirmationDialog(
                "이 사진을 삭제할까요?",
                isPresented: $isConfirmingDelete,
                titleVisibility: .visible
            ) {
                Button("삭제", role: .destructive) {
                    onDelete()
                    dismiss()
                }
                Button("취소", role: .cancel) {}
            } message: {
                Text("주차 기록은 그대로 남고 사진만 삭제돼요.")
            }
        }
    }
}
