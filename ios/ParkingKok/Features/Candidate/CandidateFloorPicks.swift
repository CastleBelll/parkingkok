import Foundation

/// The three quick picks on the confirmation screen (docs/10_DESIGN_UX_SPEC.md §7a).
///
/// > The picks come from **the floors this user has saved before**, most recent first —
/// > local history, no network, no guessing.
///
/// A pure function over records the app already holds, which is what makes "no network"
/// structural rather than a promise: there is no parameter here a request could be built
/// from. A user who always parks on B3 sees B3 because B3 is what they saved, not because
/// anything predicted it.
enum CandidateFloorPicks {
    /// §7a: three, and fewer when there are fewer. A first-ever run yields none, and the
    /// screen then shows only `직접 입력`.
    static let limit = 3

    /// Distinct floors, most recently saved first.
    ///
    /// Ordering is by `startedAt` rather than by how often a floor appears: §7a says
    /// "most recent first", and a frequency ranking would keep offering last year's
    /// office after a move.
    ///
    /// Free-text floors are included. `주차타워 2` is a floor the user typed and saved, and
    /// dropping it would silently hide the only label that describes some car parks — the
    /// `-`/`+` stepper is what free text costs (FR-005), not the ability to be reused.
    static func picks(from sessions: [ParkingSession], limit: Int = limit) -> [FloorValue] {
        var seen = Set<FloorValue>()
        var picks: [FloorValue] = []
        for session in sessions.sorted(by: { $0.startedAt > $1.startedAt }) {
            guard let floor = session.floor, seen.insert(floor).inserted else { continue }
            picks.append(floor)
            if picks.count == limit {
                break
            }
        }
        return picks
    }
}
