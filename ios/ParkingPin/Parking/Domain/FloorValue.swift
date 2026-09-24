import Foundation

/// Which kind of floor the raw text turned out to be (docs/06 §2 `floorKind`).
enum FloorKind: String, Sendable, Codable, CaseIterable {
    case basement
    case ground
    /// Anything that did not parse — `P3`, `옥상`, `주차타워 2`. Kept verbatim.
    case freeText
}

/// A parsed floor (FR-005, docs/02_PRODUCT_SCOPE_AND_FLOWS.md §6).
///
/// The raw text is always preserved: §6 says "normalize only when unambiguous, preserve
/// raw text", so `B3` typed as `지하 3층` still displays as the user's own words would if
/// it had not parsed, and round-trips through storage untouched.
///
/// Floors are modelled as a **signed level with no zero** — `B3` is -3, `1F` is +1.
/// That is what makes `+` from `B1` land on `1F` instead of inventing a `B0`, and it is
/// the only place the adjacency of the two kinds has to be expressed.
struct FloorValue: Sendable, Equatable, Hashable {
    /// Exactly what the user typed, trimmed of surrounding whitespace only.
    let raw: String
    let kind: FloorKind
    /// Positive magnitude — 3 for both `B3` and `3F`. `nil` for `.freeText`.
    let number: Int?

    /// Largest floor number that still normalises. Past this the text is kept as-is
    /// rather than pretending a 1000-storey car park was understood.
    static let maximumNumber = 999

    private init(raw: String, kind: FloorKind, number: Int?) {
        self.raw = raw
        self.kind = kind
        self.number = number
    }

    /// Parses user input. Returns `nil` only for text that is empty once trimmed —
    /// "no floor recorded" is a real state, and FR-006 makes floor optional.
    static func parse(_ input: String) -> FloorValue? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        for pattern in Pattern.all {
            guard let number = pattern.number(in: trimmed) else { continue }
            guard (1 ... maximumNumber).contains(number) else { break }
            return FloorValue(raw: trimmed, kind: pattern.kind, number: number)
        }
        return FloorValue(raw: trimmed, kind: .freeText, number: nil)
    }

    /// Rebuilds a stored value without re-parsing, so a record written by an older build
    /// keeps the kind it was saved with.
    static func stored(raw: String, kind: FloorKind, number: Int?) -> FloorValue {
        FloorValue(raw: raw, kind: kind, number: kind == .freeText ? nil : number)
    }

    /// Signed floor level. `nil` when the floor never parsed to a number.
    var level: Int? {
        guard let number else { return nil }
        return kind == .basement ? -number : number
    }

    /// FR-005: "numeric parseable floor만 widget +/- 지원".
    var isSteppable: Bool {
        level != nil
    }

    /// Normalised label — `B3`, `3F`, or the raw text when it never parsed.
    var displayText: String {
        switch kind {
        case .basement: "B\(number ?? 0)"
        case .ground: "\(number ?? 0)F"
        case .freeText: raw
        }
    }

    /// Spoken form for VoiceOver (docs/10 §12: `현재 주차 위치, 지하 3층`).
    var accessibilityText: String {
        switch kind {
        case .basement: "지하 \(number ?? 0)층"
        case .ground: "지상 \(number ?? 0)층"
        case .freeText: raw
        }
    }

    /// One floor up (`delta > 0`) or down, skipping the level that does not exist.
    ///
    /// Returns `nil` when there is nothing to step — free text, or already at the
    /// outermost floor — so the caller can disable the control rather than offer a
    /// button that quietly does nothing.
    func stepped(by delta: Int) -> FloorValue? {
        guard let level, delta != 0 else { return nil }
        var next = level + delta
        // The signed model has no zero: crossing it means changing kind.
        if next == 0 {
            next = delta > 0 ? 1 : -1
        }
        let magnitude = abs(next)
        guard (1 ... Self.maximumNumber).contains(magnitude) else { return nil }

        let kind: FloorKind = next < 0 ? .basement : .ground
        // The stepped floor is normalised by construction, so its own label is the most
        // faithful raw text available — the user's original words no longer describe it.
        let raw = kind == .basement ? "B\(magnitude)" : "\(magnitude)F"
        return FloorValue(raw: raw, kind: kind, number: magnitude)
    }
}

/// The accepted spellings from docs/02 §6.
private struct Pattern {
    let kind: FloorKind
    private let regex: NSRegularExpression

    /// Every pattern is anchored and has exactly one capture group: the magnitude.
    /// Built from literals that are known-good, so a failure here is a programmer error
    /// caught by the floor tests rather than something a user can reach.
    init?(kind: FloorKind, pattern: String) {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return nil
        }
        self.kind = kind
        self.regex = regex
    }

    func number(in text: String) -> Int? {
        let range = NSRange(text.startIndex ..< text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, options: [], range: range),
              match.numberOfRanges == 2,
              let captured = Range(match.range(at: 1), in: text)
        else {
            return nil
        }
        return Int(text[captured])
    }

    /// Order matters only in that every pattern is mutually exclusive by anchoring.
    static let all: [Pattern] = [
        // B3 / b 3 / B4F — the last is redundant and real: a pillar paints `428` over
        // `B4F`, and read as free text it loses a floor the wall states plainly.
        Pattern(kind: .basement, pattern: #"^[Bb]\s*(\d{1,3})\s*[Ff]?$"#),
        // 지하3 / 지하 3층
        Pattern(kind: .basement, pattern: #"^지하\s*(\d{1,3})\s*층?$"#),
        // 3F / 3 f
        Pattern(kind: .ground, pattern: #"^(\d{1,3})\s*[Ff]$"#),
        // 3층 / 지상 3층
        Pattern(kind: .ground, pattern: #"^(?:지상\s*)?(\d{1,3})\s*층$"#),
        Pattern(kind: .ground, pattern: #"^지상\s*(\d{1,3})$"#),
        // A bare number. Read as a floor above ground, which is what someone typing `3`
        // into a floor field means; the alternative is a value the `-`/`+` keys refuse
        // to touch for no reason the user can see.
        Pattern(kind: .ground, pattern: #"^(\d{1,3})$"#)
    ].compactMap(\.self)
}
