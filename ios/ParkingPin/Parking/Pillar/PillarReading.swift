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

    /// `A구역`, as written (§6a "the zone").
    let zone: String?

    /// `142` from `142번`, digits only, because that is what the field holds.
    let spot: String?

    init(floorText: String?, zone: String? = nil, spot: String? = nil) {
        self.floorText = floorText
        self.zone = zone
        self.spot = spot
    }

    /// The ordinary outcome. §6a: "Recognition failing is the normal case, not an error."
    static let none = PillarReading(floorText: nil)

    var isEmpty: Bool {
        floorText == nil && zone == nil && spot == nil
    }

    /// The draft a form opens with. Empty when nothing was read, which is byte for byte
    /// the draft the form opens with today.
    var draft: ManualParkingDraft {
        ManualParkingDraft(floorText: floorText ?? "", zone: zone ?? "", spot: spot ?? "")
    }

    /// What to put in a floor field that currently holds `current`, or `nil` for "leave
    /// the form alone and do not move the focus".
    ///
    /// §6a fills "the fields the user was going to fill anyway" — a field the user has
    /// already filled is not one of them, so an existing value always wins over a photo.
    /// That is also why the detail screen does not re-ask on a record that names a floor.
    func suggestedFloorText(over current: String) -> String? {
        suggested(floorText, over: current)
    }

    func suggestedZone(over current: String) -> String? {
        suggested(zone, over: current)
    }

    func suggestedSpot(over current: String) -> String? {
        suggested(spot, over: current)
    }

    private func suggested(_ value: String?, over current: String) -> String? {
        guard let value else { return nil }
        guard current.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return value
    }
}

/// One painted row, and how tall it was drawn.
///
/// The height is the only thing in a photo that says which pillar is nearest, and the
/// nearest is the one the car is at. A fraction of the image height, so it is comparable
/// within one photo and meaningless across two; zero means the reader did not measure.
/// Mirrors Android's `PillarLine`.
struct PillarLine: Sendable, Equatable {
    let text: String
    let height: Double

    init(_ text: String, height: Double = 0) {
        self.text = text
        self.height = height
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
    /// Everything a recogniser saw, turned into the one suggestion the form is offered.
    ///
    /// The order is the rule and it is not obvious, which is why it lives here rather than
    /// in the reader: the floor decides first, and whatever it took is then withheld from
    /// the zone and the bay. Mirrors Android's `PillarTextParser.parse`.
    static func reading(from observed: [PillarLine]) -> PillarReading {
        let lines = observed.map(\.text)
        let heights = paintedHeights(of: observed)
        let floorText = floorText(fromLines: lines, heights: heights)
        // What the floor took: the text it chose, plus — when that text was corrected from
        // a misread badge — the digits it was corrected from. Neither may come back as the
        // bay or as the pillar's own number (§6a).
        var used = Set(floorText.map { [$0] } ?? [])
        if let floorText, !lines.contains(floorText) {
            used.insert("8" + floorText.dropFirst())
        }
        let (zone, spot) = zoneAndSpot(fromLines: lines, excluding: used, heights: heights)
        return PillarReading(floorText: floorText, zone: zone, spot: spot)
    }

    /// How big each token was painted, tallest wins.
    ///
    /// **Every word of a row inherits the row's height**, not only the row as a whole. A
    /// pillar paints `02` over `B2` and the recogniser returns the pair as one observation
    /// `02 02`; without this the words it splits into have no size at all, and the rules
    /// that pick the nearest pillar and the nearest bay have nothing to compare.
    private static func paintedHeights(of observed: [PillarLine]) -> [String: Double] {
        observed.reduce(into: [String: Double]()) { heights, line in
            for token in [line.text] + line.text.split(whereSeparator: \.isWhitespace).map(String.init) {
                heights[token] = max(heights[token] ?? 0, line.height)
            }
        }
    }

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
    static func floorText(fromLines lines: [String], heights: [String: Double] = [:]) -> String? {
        lines.lazy.compactMap(candidate(in:)).first ?? badge(in: lines, heights: heights)
    }

    /// The floor badge that every pillar carries, when the recogniser turned its `B` into an
    /// `8` (docs/02 §6a).
    ///
    /// Measured on the phone, photographing a B2 garage numbered B14–B17:
    ///
    /// ```text
    /// "10/ C13", "a", "82", "B17", "B", "82", "B16", "[", "82", "B B15", "814", "82"
    /// ```
    ///
    /// `B2` was never read — it came back as `82` four times. `B`→`8` is the ordinary OCR
    /// confusion, and on its own it is not enough to act on: a bare number on a pillar is
    /// the bay, and rewriting every `82` into `B2` would invent a floor out of a bay number.
    ///
    /// **Repetition is what makes it safe.** The floor badge is identical on every pillar in
    /// frame; bay and pillar numbers are all different. So a digit run is re-read as a floor
    /// when it appears more than once *and* the corrected value is a floor a garage has.
    ///
    /// **Size is the other thing that makes it safe**, and repetition alone was not enough.
    /// A close-up of one pillar was supposed to read `B1` outright, and on a photo of a B1
    /// wall it did not: Vision returned a single `81` and nothing else, so the badge rule
    /// could not fire and the app offered bay 81 for a car on B1. There is no repetition to
    /// wait for in a photo with one pillar in it — what there is instead is a number painted
    /// across an eighth of the frame, which no bay number ever is. So a lone run also counts
    /// when it was painted at least [largeBadgeHeight] tall. Both routes still require the
    /// corrected value to be a floor a garage has, which is what keeps `814` out.
    private static func badge(in lines: [String], heights: [String: Double]) -> String? {
        var counts: [String: Int] = [:]
        for word in lines.flatMap({ $0.split(whereSeparator: \.isWhitespace) }) {
            let token = String(word)
            guard token.count >= 2, token.allSatisfy(\.isNumber), token.hasPrefix("8") else { continue }
            counts[token, default: 0] += 1
        }
        return counts
            .filter { $0.value >= 2 || (heights[$0.key] ?? 0) >= largeBadgeHeight }
            // Most repeated first, then the biggest, then the shallower floor: `82` before
            // `83` is a coin toss worth deciding the same way every time.
            .sorted {
                ($0.value, heights[$0.key] ?? 0, $1.key) > ($1.value, heights[$1.key] ?? 0, $0.key)
            }
            .lazy
            .map { "B" + $0.key.dropFirst() }
            .first { text in
                guard let parsed = FloorValue.parse(text) else { return false }
                return isPlausibleFloor(parsed)
            }
    }

    /// How tall, as a fraction of the image, a single `8`-prefixed run has to be painted
    /// before it is believed to be the floor badge with nothing to compare it against.
    ///
    /// Measured across twelve real pillar photos: the `81` that is a B1 badge filling a
    /// close-up came back at 0.129, and every digit run that was *not* a floor — `814` at
    /// 0.043, the repeated `82` at 0.027, a wall-sign `81` at 0.020 — sat below a third of
    /// that. 0.08 is the middle of a gap with nothing in it.
    static let largeBadgeHeight = 0.08

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

    /// `A구역`, `A 구역`, `가구역`. The label before 구역 is short by nature.
    ///
    /// The literal word is required. Without it every two-character token on a wall of
    /// signage is a zone, and docs/02 §6a's "partial read → leave the rest blank" is the
    /// better answer than a guess. Mirrored exactly in Android's `PillarTextParser`; the
    /// grammar lives in docs/02 §6a so the two cannot drift.
    private static var zonePattern: Regex<Substring> { /^[가-힣A-Za-z0-9]{1,6}\s*구역$/ }

    /// `142`, `142번`. Kept as digits, because that is what the field holds.
    private static var spotPattern: Regex<(Substring, Substring)> { /^(\d{1,4})번?$/ }

    /// `B17`, `C13`, `가12` — the number painted on the pillar itself.
    ///
    /// Not a zone in the `A구역` sense, and it is what the user would write down anyway:
    /// in a garage whose pillars are labelled, "B17" *is* where the car is. It is offered
    /// only when the photo settles which pillar is meant:
    ///
    /// * **The floor badge is excluded.** It repeats on every pillar in frame while pillar
    ///   numbers all differ, so anything appearing more than once is the badge, not a
    ///   pillar — and so are the digits the floor correction already consumed.
    /// * **Several distinct labels means none.** A wide shot catching B14 through B17
    ///   cannot say which one the car is at, and a confident wrong pillar sends the user to
    ///   the wrong end of the floor. Photographing the pillar in front of them leaves one,
    ///   and one is answerable.
    private static func pillarLabel(
        among words: [String],
        excluding used: Set<String>,
        heights: [String: Double]
    ) -> String? {
        var counts: [String: Int] = [:]
        for word in words where word.wholeMatch(of: pillarLabelPattern) != nil {
            counts[word, default: 0] += 1
        }
        let labels = counts
            .filter { $0.value == 1 && !used.contains($0.key) }
            .map(\.key)
        if labels.count == 1 { return labels.first }

        // Several pillars in frame: the one the car is at is the one nearest the camera,
        // and the nearest is the one painted largest. Ties are left alone — two pillars the
        // same size are two pillars the photo cannot choose between.
        let ranked = labels
            // A height of zero is "not measured", not "flat": it must not rank.
            .compactMap { label in heights[label].flatMap { $0 > 0 ? (label, $0) : nil } }
            .sorted { $0.1 > $1.1 }
        guard let nearest = ranked.first else { return nil }
        guard let next = ranked.dropFirst().first else { return nearest.0 }
        return nearest.1 >= next.1 * nearestMargin ? nearest.0 : nil
    }

    /// How much taller the nearest label has to be before it is believed to be nearest.
    ///
    /// Measured on the wide shot that raised this: `B17` at 0.061 against `BB15` at 0.050,
    /// a ratio of 1.22. Below this the photo is looking down a row of equally distant
    /// pillars and has no answer.
    static let nearestMargin = 1.15

    /// Whether a digit run is one of *this wall's* pillar labels with its `B` read as an `8`.
    ///
    /// `814` in a photo that also shows `B15`, `B16` and `B17` is `B14`, not bay 814 — the
    /// same `B`→`8` confusion the badge suffers, and it was being offered as the bay. The
    /// test is the shape the wall is already using: one letter and two digits here, so a
    /// three-digit run beginning `8` is one of them. In a photo with no such labels, `814`
    /// stays what it looks like.
    private static func readsAsAPillarLabel(_ word: String, shapes: Set<String>) -> Bool {
        guard word.count >= 2, word.hasPrefix("8"), word.allSatisfy(\.isNumber) else { return false }
        return shapes.contains(shape(of: "B" + word.dropFirst()))
    }

    /// `B17` and `C13` share a shape; `가12` does not, and neither does `B7`.
    private static func shape(of word: String) -> String {
        word.map { $0.isNumber ? "9" : ($0.isASCII ? "A" : "가") }.joined()
    }

    /// One or two letters — Latin or Hangul — then one to three digits, and nothing else.
    private static var pillarLabelPattern: Regex<Substring> { /^[가-힣A-Za-z]{1,2}\d{1,3}$/ }

    /// A pillar that paints its letter and its number on two separate rows.
    ///
    /// `A` above `47` is one label the recogniser returned as two observations, and the
    /// rule above cannot see it: `A` is not `A47`. Without this the `A` was dropped and the
    /// user was offered bay 47 on a floor with an A and a B end.
    ///
    /// Only when the photo contains no pillar label at all. In a wide shot that *does* —
    /// `B17`, `B16`, `B B15` — a stray `B` is the left-over of a label already read, and
    /// offering it as the zone would name a pillar that is not there.
    ///
    /// **Latin capitals only, and not Hangul.** A wall of Korean signage is made of
    /// two-syllable words — `안내`, `출구`, `주차` — and every one of them is a two-character
    /// token that is not a zone. Hangul zones are written `가구역` on the wall anyway, which
    /// the 구역 rule above already reads.
    /// **And painted large enough to be one.** A garage is full of small letters — the `P`
    /// on a wall sign forty metres away came back at 0.013 of the image and was offered as
    /// the zone. A letter that names where the car is is painted to be read from across the
    /// floor; measured on the pillar that raised this rule, its `A` filled 0.10 to 0.14. A
    /// reader that measured nothing therefore offers no lone letter, which is the same
    /// stance the nearest-pillar rule takes.
    private static var loneLetterPattern: Regex<Substring> { /^[A-Z]{1,2}$/ }

    static let loneLetterMinHeight = 0.05

    private static func loneLetterZone(
        among words: [String],
        excluding used: Set<String>,
        heights: [String: Double]
    ) -> String? {
        var counts: [String: Int] = [:]
        for word in words where word.wholeMatch(of: loneLetterPattern) != nil {
            counts[word, default: 0] += 1
        }
        let letters = counts
            .filter { $0.value == 1 && !used.contains($0.key) }
            .filter { (heights[$0.key] ?? 0) >= loneLetterMinHeight }
            .map(\.key)
        return letters.count == 1 ? letters.first : nil
    }

    /// The one- and two-word phrases of a line, longer first at each position.
    ///
    /// Two words because `A 구역` is one value written with a space in it, and splitting on
    /// whitespace leaves `A` — which the lone-letter rule would then offer as the zone,
    /// dropping the 구역 the wall painted. Mirrors Android's `windowsOf`.
    private static func windows(of line: String) -> [String] {
        let words = line.split(whereSeparator: \.isWhitespace).map(String.init)
        return words.indices.flatMap { index -> [String] in
            let pair = index + 1 < words.count ? ["\(words[index]) \(words[index + 1])"] : []
            return pair + [words[index]]
        }
    }

    /// The zone and bay a pillar states, if it states them (§6a).
    ///
    /// `used` is what the floor already took — the text it chose and, on a wide shot, the
    /// digit run that text was corrected from. Without it the same misread that becomes
    /// 지하 2층 is handed back as bay 82 or as the pillar's own number.
    static func zoneAndSpot(
        fromLines lines: [String],
        excluding used: Set<String>,
        heights: [String: Double] = [:]
    ) -> (String?, String?) {
        let words = lines.flatMap { $0.split(whereSeparator: \.isWhitespace) }.map(String.init)
        let labelShapes = Set(
            words.compactMap { $0.wholeMatch(of: pillarLabelPattern) != nil ? shape(of: $0) : nil }
        )
        var bays: [(digits: String, height: Double)] = []
        for word in words where !used.contains(word) && !readsAsAPillarLabel(word, shapes: labelShapes) {
            guard let digits = word.wholeMatch(of: spotPattern)?.1 else { continue }
            let height = heights[word] ?? 0
            if let index = bays.firstIndex(where: { $0.digits == digits }) {
                bays[index].height = max(bays[index].height, height)
            } else {
                bays.append((String(digits), height))
            }
        }
        let bay = nearestBay(among: bays)
        let bayHeight = bay.flatMap { digits in bays.first { $0.digits == digits }?.height } ?? 0
        return (zone(named: lines, words: words, used: used, heights: heights,
                     labelShapes: labelShapes, bayHeight: bayHeight), bay)
    }

    /// Which pillar the photo is of, in the three ways a wall says it.
    ///
    /// `A구역` first because it is the only one the contract spells out, then the pillar's
    /// own number, then a lone letter — each a weaker claim than the one before it.
    private static func zone(
        named lines: [String],
        words: [String],
        used: Set<String>,
        heights: [String: Double],
        labelShapes: Set<String>,
        bayHeight: Double
    ) -> String? {
        if let named = lines.flatMap(windows(of:)).first(where: { $0.wholeMatch(of: zonePattern) != nil }) {
            return named.replacing(/\s+/, with: "")
        }
        if let label = pillarLabel(among: words, excluding: used, heights: heights) {
            // **A label painted far smaller than the bay is a different pillar.** A frame
            // holding `B1 18` across a fifth of the image and `B119` across a tenth is one
            // pillar in front of the camera and the next one down the row; naming the far
            // one beside the near one's bay sends the user to neither.
            let labelHeight = heights[label] ?? 0
            guard labelHeight > 0, bayHeight >= labelHeight * nearestMargin else { return label }
            return nil
        }
        // Only when the photo shows no pillar number at all — see [loneLetterPattern].
        guard labelShapes.isEmpty else { return nil }
        return loneLetterZone(among: words, excluding: used, heights: heights)
    }

    /// Which bay, when a photo paints several.
    ///
    /// The same rule as the pillar label, for the same reason. A frame looking down a row
    /// of bays shows `02`, `03` and `04`, and the car is at the one nearest the camera —
    /// the one painted largest. Taking the first the recogniser returned picked `04`, the
    /// far end of the row, on a real photo of a car parked at `02`.
    ///
    /// A reader that measured nothing reports zero for everything, and then the order is
    /// all there is; that is the old behaviour and it stays for that case.
    private static func nearestBay(among bays: [(digits: String, height: Double)]) -> String? {
        guard bays.count > 1 else { return bays.first?.digits }
        let ranked = bays.filter { $0.height > 0 }.sorted { $0.height > $1.height }
        guard let nearest = ranked.first else { return bays.first?.digits }
        guard let next = ranked.dropFirst().first else { return nearest.digits }
        return nearest.height >= next.height * nearestMargin ? nearest.digits : nil
    }

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
