import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers
@testable import ParkingPin

/// Synthetic source images for the photo tests.
///
/// Generated rather than committed: a fixture binary in the repo would be one more thing
/// to keep in step with the downsampler, and the only property these tests care about is
/// the pixel dimensions.
enum TestImage {
    /// A JPEG of exactly `width` × `height`, with enough variation that the encoder
    /// cannot collapse it to a single-block image.
    static func jpegData(width: Int, height: Int) throws -> Data {
        let image = try make(width: width, height: height)
        let encoded = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            encoded,
            UTType.jpeg.identifier as CFString,
            1,
            nil
        ) else {
            throw TestImageError.encodeFailed
        }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else {
            throw TestImageError.encodeFailed
        }
        return encoded as Data
    }

    /// Reads the dimensions back out of encoded bytes without decoding the pixels.
    static func pixelSize(of data: Data) throws -> (width: Int, height: Int) {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int
        else {
            throw TestImageError.unreadable
        }
        return (width, height)
    }

    /// Every property dictionary the encoded bytes carry, keyed as ImageIO names them.
    static func allProperties(of data: Data) throws -> [String: Any] {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        else {
            throw TestImageError.unreadable
        }
        return Dictionary(uniqueKeysWithValues: properties.map { ($0.key as String, $0.value) })
    }

    private static func make(width: Int, height: Int) throws -> CGImage {
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else {
            throw TestImageError.contextFailed
        }
        let bandHeight = max(height / 8, 1)
        for band in 0 ..< 8 {
            context.setFillColor(red: Double(band) / 8, green: 0.4, blue: 0.8, alpha: 1)
            context.fill(CGRect(x: 0, y: band * bandHeight, width: width, height: bandHeight))
        }
        guard let image = context.makeImage() else {
            throw TestImageError.contextFailed
        }
        return image
    }
}

enum TestImageError: Error {
    case contextFailed
    case encodeFailed
    case unreadable
}

/// A throwaway directory that cleans itself up.
///
/// The photo store writes real files; every test that touches it gets its own root so
/// they cannot see each other's orphans.
final class TemporaryDirectory {
    let url: URL

    init() throws {
        url = FileManager.default.temporaryDirectory
            .appending(path: "pk-photo-tests-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    deinit {
        try? FileManager.default.removeItem(at: url)
    }

    func makePhotoStore() -> FileSystemParkingPhotoStore {
        FileSystemParkingPhotoStore(
            root: url.appending(path: FileSystemParkingPhotoStore.directoryName, directoryHint: .isDirectory)
        )
    }
}

/// A photo store that records what it was asked to do and never touches the filesystem.
///
/// Used where the assertion is about *ordering* — which of the record and the file goes
/// first — rather than about the bytes.
final class SpyParkingPhotoStore: ParkingPhotoStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var saved: [UUID: Data] = [:]
    private var removedPaths: [String] = []
    private var orphanSweeps: [Set<String>] = []
    private var saveError: ParkingPhotoError?

    init(saveError: ParkingPhotoError? = nil) {
        self.saveError = saveError
    }

    var savedRecordIDs: [UUID] {
        lock.withLock { Array(saved.keys) }
    }

    var removed: [String] {
        lock.withLock { removedPaths }
    }

    var sweeps: [Set<String>] {
        lock.withLock { orphanSweeps }
    }

    func save(_ imageData: Data, for recordID: UUID) async throws -> String {
        try lock.withLock {
            if let saveError {
                throw saveError
            }
            saved[recordID] = imageData
            return FileSystemParkingPhotoStore.relativePath(for: recordID)
        }
    }

    func load(_ relativePath: String) async throws -> ParkingPhoto {
        let match = lock.withLock {
            saved.first { FileSystemParkingPhotoStore.relativePath(for: $0.key) == relativePath }?.value
        }
        guard let match else { throw ParkingPhotoError.notFound(relativePath) }
        return ParkingPhoto(data: match, savedAt: TestTime.reference)
    }

    func remove(_ relativePath: String) async throws {
        lock.withLock {
            removedPaths.append(relativePath)
            saved = saved.filter { FileSystemParkingPhotoStore.relativePath(for: $0.key) != relativePath }
        }
    }

    func removeOrphans(keeping keptPaths: Set<String>) async throws {
        lock.withLock {
            orphanSweeps.append(keptPaths)
            saved = saved.filter { keptPaths.contains(FileSystemParkingPhotoStore.relativePath(for: $0.key)) }
        }
    }
}

/// A photo store whose `save` waits until the test lets it finish.
///
/// Stands in for encoding a 12-megapixel pillar photo, which takes long enough on a phone
/// for the one-shot location fix to land in the middle of it (2026-09-24).
final class GatedParkingPhotoStore: ParkingPhotoStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var started: CheckedContinuation<Void, Never>?
    private var hasStarted = false
    private var release: CheckedContinuation<Void, Never>?

    /// Returns once a `save` is suspended inside the store.
    func waitUntilSaving() async {
        await withCheckedContinuation { continuation in
            let alreadyStarted = lock.withLock {
                if hasStarted { return true }
                started = continuation
                return false
            }
            if alreadyStarted { continuation.resume() }
        }
    }

    /// Lets the suspended `save` return.
    func finishSaving() {
        let pending = lock.withLock {
            let pending = release
            release = nil
            return pending
        }
        pending?.resume()
    }

    func save(_ imageData: Data, for recordID: UUID) async throws -> String {
        await withCheckedContinuation { continuation in
            let waiter = lock.withLock {
                release = continuation
                hasStarted = true
                let waiter = started
                started = nil
                return waiter
            }
            waiter?.resume()
        }
        return FileSystemParkingPhotoStore.relativePath(for: recordID)
    }

    func load(_ relativePath: String) async throws -> ParkingPhoto {
        throw ParkingPhotoError.notFound(relativePath)
    }

    func remove(_ relativePath: String) async throws {}

    func removeOrphans(keeping keptPaths: Set<String>) async throws {}
}
