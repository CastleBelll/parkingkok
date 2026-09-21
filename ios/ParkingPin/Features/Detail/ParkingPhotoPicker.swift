import PhotosUI
import SwiftUI
import UIKit

/// Where the user wants the one photo to come from (FR-007).
///
/// `docs/04 §10` names both sources. They need different machinery — the camera is a
/// `UIImagePickerController`, the library is SwiftUI's out-of-process `PhotosPicker` —
/// so the choice is made before either is presented.
enum ParkingPhotoSource: String, Identifiable, CaseIterable {
    case camera
    case library

    var id: String {
        rawValue
    }

    /// The simulator has no camera, and a user can turn the app's camera access off in
    /// Settings without that being an app failure (CLAUDE.md: 권한 거부는 앱 전체
    /// failure가 아니다). Either way the library entry stays.
    @MainActor
    static var available: [ParkingPhotoSource] {
        UIImagePickerController.isSourceTypeAvailable(.camera) ? allCases : [.library]
    }

    var title: String {
        switch self {
        case .camera: "사진 촬영"
        case .library: "앨범에서 선택"
        }
    }

    var systemImage: String {
        switch self {
        case .camera: "camera"
        case .library: "photo.on.rectangle"
        }
    }
}

/// Camera capture, wrapped for SwiftUI.
///
/// `UIImagePickerController` rather than a custom `AVCaptureSession`: this milestone
/// needs one still photo with the system's own shutter UI, and an in-app capture screen
/// would be a second camera implementation to maintain for no product difference.
///
/// It hands back a `UIImage`, so the bytes are re-encoded here before they leave. That
/// costs one JPEG encode of an already-decoded capture (~12MP) and buys a single
/// downsample implementation — `ParkingPhotoDownsampler` sees `Data` from both sources
/// and is the only place image size is decided.
struct CameraPhotoPicker: UIViewControllerRepresentable {
    /// Full quality: this is an intermediate the downsampler immediately re-encodes, so
    /// compressing here would only stack two lossy passes.
    static let handoffQuality: CGFloat = 1

    let onPicked: (Data) -> Void
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.allowsEditing = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_: UIImagePickerController, context _: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPicked: onPicked, onCancel: onCancel)
    }

    @MainActor
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        private let onPicked: (Data) -> Void
        private let onCancel: () -> Void

        init(onPicked: @escaping (Data) -> Void, onCancel: @escaping () -> Void) {
            self.onPicked = onPicked
            self.onCancel = onCancel
        }

        func imagePickerController(
            _: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            guard let image = info[.originalImage] as? UIImage,
                  let data = image.jpegData(compressionQuality: CameraPhotoPicker.handoffQuality)
            else {
                onCancel()
                return
            }
            onPicked(data)
        }

        func imagePickerControllerDidCancel(_: UIImagePickerController) {
            onCancel()
        }
    }
}
