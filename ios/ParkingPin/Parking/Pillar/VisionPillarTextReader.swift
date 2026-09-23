import Foundation
import UIKit
import Vision

/// docs/02 §6a's on-device read, on Vision.
///
/// ### On-device, with nothing to download
/// §6a requires the recognition model to be present in an underground car park with no
/// network: "a model that downloads on demand would be missing at exactly the moment it
/// is needed." Vision's text models ship inside iOS, so there is no first-use fetch to
/// avoid and no bundled artifact to add — this is the iOS counterpart of Android having
/// to take ML Kit's bundled model rather than the Play-services one.
///
/// Korean is supported at `.accurate` and **only** at `.accurate`: `.fast` recognises the
/// six Latin languages and nothing else, verified against
/// `RecognizeTextRequest.supportedRecognitionLanguages` on iOS 18. A `.fast` reader would
/// silently never read a Korean pillar, which is the whole feature.
///
/// Nothing recognised here is logged, reported or persisted (§6a "Not analytics", docs/17
/// §3 — floor and spot are forbidden properties). The string reaches one text field and
/// dies there unless the user saves it.
struct VisionPillarTextReader: PillarTextReading {
    /// §6a: "the model is unavailable or takes too long → same as no text found". The
    /// user is standing at a pillar with a phone in one hand; a read that has not finished
    /// by now is worse than the empty form it is delaying.
    ///
    /// Sized against the **cold** cost, not the warm one. A first recognition measured
    /// ~5.2 s on an iOS 18 simulator against ~1.0 s for every one after it, almost all of
    /// it loading the model; a four-second deadline silently lost every first read, which
    /// is exactly the read that decides whether a user believes the feature works.
    /// `prepare()` is what makes the ordinary read the warm one.
    static let timeout: Duration = .seconds(6)

    /// Korean first — it is the language the wall is in — with English beside it for
    /// `B3`, `P2`, `LEVEL 3`. Both are recognised in one pass.
    static let languages: [Locale.Language] = [
        Locale.Language(identifier: "ko-KR"),
        Locale.Language(identifier: "en-US")
    ]

    private let timeout: Duration

    init(timeout: Duration = VisionPillarTextReader.timeout) {
        self.timeout = timeout
    }

    /// One recognition on a throwaway image, to pay the model load somewhere the user is
    /// not waiting. Its result is discarded — only the loading matters.
    func prepare() async {
        guard let warmup = Self.warmupImage else { return }
        _ = await withTimeout(timeout) { await Self.recognise(warmup) }
    }

    func read(_ imageData: Data) async -> PillarReading {
        let observations = await withTimeout(timeout) { await Self.recognise(imageData) } ?? []
        let lines = observations.map(\.text)
        // Tallest wins per token: the same label can appear twice, and what matters is the
        // biggest it was painted.
        let heights = observations.reduce(into: [String: Double]()) { heights, observation in
            heights[observation.text] = max(heights[observation.text] ?? 0, observation.height)
        }
        let floorText = PillarFloorSuggestion.floorText(fromLines: lines)
        // What the floor took: the text it chose, plus — when that text was corrected from
        // a misread badge — the digits it was corrected from. Neither may come back as the
        // bay or as the pillar's own number (§6a).
        var used = Set(floorText.map { [$0] } ?? [])
        if let floorText, !lines.contains(floorText) {
            used.insert("8" + floorText.dropFirst())
        }
        let (zone, spot) = PillarFloorSuggestion.zoneAndSpot(
            fromLines: lines,
            excluding: used,
            heights: heights
        )
        #if PK_DEV
            PillarReadDiagnostics.record(lines: lines, chose: floorText, zone: zone, spot: spot)
        #endif
        return PillarReading(floorText: floorText, zone: zone, spot: spot)
    }

    /// A single blank pixel. Enough to make Vision load the recognisers, small enough to
    /// cost nothing once they are loaded.
    private static let warmupImage: Data? = {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 32, height: 32))
        return renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 32, height: 32))
        }.jpegData(compressionQuality: 1)
    }()

    /// One painted row, and how tall it was drawn — the only thing in a photo that says
    /// which pillar is nearest.
    private struct Observed: Sendable {
        let text: String
        /// A fraction of the image height, so it is comparable within one photo and
        /// meaningless across two.
        let height: Double
    }

    private static func recognise(_ imageData: Data) async -> [Observed] {
        var request = RecognizeTextRequest()
        // Korean is not in the `.fast` model's language list, so this is not a quality
        // preference — it is the only level that can read the wall at all.
        request.recognitionLevel = .accurate
        request.recognitionLanguages = languages
        // A pillar is not prose. Language correction exists to turn near-misses into
        // words, and `B3` has no word it wants to become; left on, it is one more way a
        // correct read turns into a wrong suggestion.
        request.usesLanguageCorrection = false
        do {
            let observations = try await request.perform(on: imageData)
            // One string per painted row. `topCandidate` alone: a second-choice reading
            // is precisely the `83` for `B3` that §6a says must never reach the record,
            // and offering it would be volunteering the misread.
            return observations.compactMap { observation -> Observed? in
                guard let text = observation.topCandidates(1).first?.string else { return nil }
                return Observed(text: text, height: observation.boundingBox.height)
            }
        } catch {
            // §6a: a model that is unavailable is the same outcome as a wall with no text
            // on it. Nothing is said to the user and nothing is reported.
            AppLog.lifecycle.notice("pillar text recognition unavailable")
            return []
        }
    }

    /// Races the read against §6a's deadline, returning `nil` if the deadline wins.
    private func withTimeout(
        _ duration: Duration,
        _ work: @escaping @Sendable () async -> [Observed]
    ) async -> [Observed]? {
        await withTaskGroup(of: [Observed]?.self) { group in
            group.addTask { await work() }
            group.addTask {
                try? await Task.sleep(for: duration)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }
}
