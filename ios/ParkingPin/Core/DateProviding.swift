import Foundation

/// Source of "now" for every time-dependent decision in the detection stack.
///
/// docs/16_CODING_STANDARDS.md §8: inject the clock, never sleep in tests. Detection
/// reasons about windows that are minutes to hours wide, so tests must be able to move
/// time instantly.
protocol DateProviding: Sendable {
    var now: Date { get }
}

/// Production clock.
struct SystemDateProvider: DateProviding {
    var now: Date {
        Date()
    }
}
