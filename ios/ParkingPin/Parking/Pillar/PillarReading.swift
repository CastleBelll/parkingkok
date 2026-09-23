import Foundation

/// What a photo of a pillar offered, before the user has seen it (docs/02 §6a).
///
/// **A suggestion, never a saved value.** §6a: "Nothing is auto-saved from a photo: a
/// misread `B3` as `83` that silently became the record would be worse than typing." So
/// this type holds text for a form field and has no path to the store at all.
///
/// ### Why there is only a floor
/// §6a says the result is "parsed with the existing rules — floor by docs/02 §6". The
/// floor is the only field docs/02 gives rules for; there is no zone or bay grammar
/// anywhere in the contract, and inventing one here is how this platform and Android
/// would start disagreeing about the same wall. A pillar photo therefore fills the floor
/// and leaves the rest exactly as blank as it is today — which is also §6a's "partial
/// read → fill what parsed, leave the rest blank".
struct PillarReading: Sendable, Equatable {
    /// Raw recognised text that docs/02 §6's parser accepted as a floor, kept verbatim so
    /// the field shows what was on the wall rather than a normalisation of it.
    let floorText: String?

    /// The ordinary outcome. §6a: "Recognition failing is the normal case, not an error."
    static let none = PillarReading(floorText: nil)

    var isEmpty: Bool {
        floorText == nil
    }

    /// The draft a form opens with. Empty when nothing was read, which is byte for byte
    /// the draft the form opens with today.
    var draft: ManualParkingDraft {
        ManualParkingDraft(floorText: floorText ?? "")
    }

    /// What to put in a floor field that currently holds `current`, or `nil` for "leave
    /// the form alone and do not move the focus".
    ///
    /// §6a fills "the fields the user was going to fill anyway" — a field the user has
    /// already filled is not one of them, so an existing value always wins over a photo.
    /// That is also why the detail screen does not re-ask on a record that names a floor.
    func suggestedFloorText(over current: String) -> String? {
        guard let floorText else { return nil }
        guard current.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return floorText
    }
}

/// Reads the floor off a pillar photo, on device.
///
/// A protocol because the screens must be testable without a camera, a photo library or
/// a Vision model — and because §6a's failure paths ("no text found", "the model is
/// unavailable or takes too long") are the ones most worth asserting.
protocol PillarTextReading: Sendable {
    /// Never throws and never reports why. §6a: a failed read opens the form "exactly as
    /// it does today, empty. No message, no spinner left behind, no 인식 실패 dialog", so
    /// there is nothing for a caller to do with an error and no way for it to leak into
    /// the UI as one.
    func read(_ imageData: Data) async -> PillarReading

    /// Loads whatever the first `read` would otherwise load, so it is not loaded while
    /// the user waits.
    ///
    /// Called when the camera or the picker is presented — the moment a read becomes
    /// near-certain and the user is busy framing a shot. Measured on an iOS 18 simulator,
    /// the first recognition costs ~5.2 s and every one after it ~1.0 s: without this the
    /// **first** pillar a user ever photographs is the one that times out, which is the
    /// worst possible one to lose.
    ///
    /// Optional, idempotent and best-effort: skipping it costs a slow read, never a wrong
    /// one.
    func prepare() async
}

extension PillarTextReading {
    func prepare() async {}
}

/// Turns recognised lines into a floor suggestion, using docs/02 §6's parser and nothing
/// else.
///
/// Separate from the Vision plumbing so the rule can be tested on strings — which is what
/// `B3` vs `83` vs `142` actually is.
enum PillarFloorSuggestion {
    /// The one line that states a plausible floor, or `nil`.
    ///
    /// **Order is not a signal, and treating it as one was a real misread.** This used to
    /// take the first line that parsed, on the reasoning that a pillar paints the floor
    /// above the bay number. Vision promises no order at all: on a photo of a B2 garage
    /// whose pillars are numbered B14–B17, the Mac returned `B2` before `B17` and the phone
    /// returned `B17` first, so the app offered 지하 17층 and ignored the floor.
    ///
    /// What separates them is not position but size — of the number, not of the text. See
    /// [isPlausibleFloor].
    static func floorText(fromLines lines: [String]) -> String? {
        lines.lazy.compactMap(candidate(in:)).first
    }

    /// Deepest basement and highest storey a floor sign is believed to state.
    ///
    /// A pillar carries two `B`-numbers in this shape — `B2` for the floor and `B17` for
    /// the pillar — and the only thing that distinguishes them on the wall is how big the
    /// number is. Korean garages bottom out around B7; a handful reach B10. Past that, a
    /// number is a bay or a pillar id, and offering it as a floor is worse than offering
    /// nothing: the user then has to notice and undo it.
    ///
    /// Field-tuning starting points, like §8's evidence weights. A genuinely deeper garage
    /// gets no suggestion and the user types the floor, which is exactly today's behaviour
    /// when a read fails.
    static let deepestBasement = 10
    static let highestStorey = 20

    private static func isPlausibleFloor(_ floor: FloorValue) -> Bool {
        guard let number = floor.number else { return false }
        switch floor.kind {
        case .basement: return number <= deepestBasement
        case .ground: return number <= highestStorey
        case .freeText: return false
        }
    }

    /// A line reduced to the floor it unambiguously states, or `nil`.
    ///
    /// Each line is also tried word by word: a pillar reads `B3 A구역 142` on one
    /// painted row, and Vision returns that row as one observation.
    private static func candidate(in line: String) -> String? {
        // **The first floor-shaped window decides the line, even when it decides against
        // one.** Falling through to a narrower window re-reads a fragment of the same sign:
        // `지하 15층` is rejected as a floor nobody has, and its second word alone is
        // `15층` — a basement turned into a storey, thirty floors from the car.
        guard let parsed = firstFloor(in: line) else { return nil }
        return isPlausibleFloor(parsed) ? parsed.raw : nil
    }

    /// The first floor this line states, plausible or not.
    ///
    /// Whole line first, then word by word: a pillar reads `B3 A구역 142` on one painted
    /// row and Vision returns that row as one observation.
    private static func firstFloor(in line: String) -> FloorValue? {
        if let whole = unambiguousFloor(in: line) {
            return whole
        }
        return line.split(whereSeparator: \.isWhitespace)
            .lazy
            .compactMap { unambiguousFloor(in: String($0)) }
            .first
    }

    /// docs/02 §6's parser, with one guard that belongs to this caller and not to the
    /// parser.
    ///
    /// `FloorValue.parse` reads a bare `142` as the 142nd floor above ground, which is the
    /// right answer for someone typing into a field labelled 층 and the wrong one for a
    /// number painted on a wall — on a pillar a bare number is the bay. So a suggestion
    /// must carry a floor marker (`B`, `지하`, `F`, `층`, `지상`); anything that parsed
    /// only because it was digits is not offered. The parser itself is untouched: FR-005,
    /// the widget stepper and the manual sheet all still accept a typed `3`.
    private static func unambiguousFloor(in text: String) -> FloorValue? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Int(trimmed) == nil else { return nil }
        guard let parsed = FloorValue.parse(trimmed), parsed.kind != .freeText else { return nil }
        return parsed
    }
}
