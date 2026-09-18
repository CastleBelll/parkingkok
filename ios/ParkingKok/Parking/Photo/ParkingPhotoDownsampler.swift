import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Why a picked image could not become a stored parking photo (docs/16 §4: framework
/// failures are mapped to domain errors at the boundary).
enum ParkingPhotoError: Error, Equatable {
    /// The picked bytes were not an image ImageIO can open.
    case unreadableImage
    case downsampleFailed
    case encodeFailed
    case directoryUnavailable(String)
    case writeFailed(String)
    /// A stored path that no longer resolves to a file — the record outlived its photo.
    case notFound(String)
}

extension ParkingPhotoError {
    /// What may be written to a log line.
    ///
    /// The case name and nothing else. The payloads are a stored file name and the
    /// description of a `FileManager`/`Data` error, and the latter routinely embeds
    /// `NSFilePath=/var/mobile/Containers/.../ParkingPhotos/<id>.heic`. CLAUDE.md puts
    /// photo paths in the same bucket as coordinates: out of logs, analytics and crash
    /// fields. The user-facing message already says what went wrong.
    var logLabel: String {
        switch self {
        case .unreadableImage: "unreadableImage"
        case .downsampleFailed: "downsampleFailed"
        case .encodeFailed: "encodeFailed"
        case .directoryUnavailable: "directoryUnavailable"
        case .writeFailed: "writeFailed"
        case .notFound: "notFound"
        }
    }
}

/// Shrinks a picked image to the one size FR-007 stores.
///
/// ImageIO rather than `UIImage` + `UIGraphicsImageRenderer`:
/// `CGImageSourceCreateThumbnailAtIndex` reads the source progressively and only ever
/// materialises the downsampled result, so a 48MP HEIC costs a few MB instead of the
/// ~190MB its full RGBA decode would (docs/11 §12 "photo memory"). The renderer path
/// has to decode the original in full before it can draw it smaller, which is the
/// allocation this milestone is specifically told not to make.
///
/// Two side effects worth naming, both wanted:
/// - `kCGImageSourceCreateThumbnailWithTransform` bakes the EXIF orientation into the
///   pixels, so the stored file is upright with no orientation tag to misread later.
/// - The thumbnail carries **no metadata at all**. A photo picked from the library
///   usually has GPS EXIF in it; this drops it. docs/06 §1 classifies coordinates as
///   local-sensitive, and a coordinate nobody kept is a coordinate nobody can leak.
enum ParkingPhotoDownsampler {
    /// FR-007: "long edge target ~1600px".
    static let maximumLongEdge = 1600

    /// HEIC at 0.8 is visually indistinguishable from source for a photo of a car park
    /// pillar and lands around 200–400KB at 1600px.
    static let compressionQuality = 0.8

    /// Downsampled HEIC bytes for `source`.
    ///
    /// Not `async` itself — it is synchronous CPU work. Callers run it off the main
    /// actor; `FileSystemParkingPhotoStore.save` is the one that does.
    static func heicData(from source: Data, maximumLongEdge: Int = Self.maximumLongEdge) throws -> Data {
        // `kCGImageSourceShouldCache: false` keeps the *source* from being decoded into
        // the cache behind our back; the thumbnail below is the only decode we pay for.
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        // The count check is not redundant: `CGImageSourceCreateWithData` hands back a
        // live, empty source for bytes that are not an image at all, so without it a
        // text file would surface as `downsampleFailed` instead of `unreadableImage`.
        guard let imageSource = CGImageSourceCreateWithData(source as CFData, sourceOptions),
              CGImageSourceGetCount(imageSource) > 0
        else {
            throw ParkingPhotoError.unreadableImage
        }
        let thumbnailOptions = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maximumLongEdge
        ] as CFDictionary
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(imageSource, 0, thumbnailOptions) else {
            throw ParkingPhotoError.downsampleFailed
        }
        return try encodeHEIC(thumbnail)
    }

    private static func encodeHEIC(_ image: CGImage) throws -> Data {
        let encoded = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            encoded,
            UTType.heic.identifier as CFString,
            1,
            nil
        ) else {
            throw ParkingPhotoError.encodeFailed
        }
        let properties = [kCGImageDestinationLossyCompressionQuality: compressionQuality] as CFDictionary
        CGImageDestinationAddImage(destination, image, properties)
        guard CGImageDestinationFinalize(destination) else {
            throw ParkingPhotoError.encodeFailed
        }
        return encoded as Data
    }
}
