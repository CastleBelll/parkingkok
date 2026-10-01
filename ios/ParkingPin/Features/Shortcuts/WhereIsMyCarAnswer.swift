import Foundation

/// What `내 차 어디?` says (docs/02 §17). One sentence Siri can read aloud and a Shortcut
/// can show: the place the home hero shows, and how long the car has been there.
///
/// Built from `ActiveParkingSnapshot` — the App Group projection the widget reads — so
/// answering never opens the app or the database, and carries nothing the widget would
/// not draw: no coordinate, no memo, no photo (docs/06 §1).
enum WhereIsMyCarAnswer {
    static let noParking = "진행 중인 주차가 없어요."

    /// - `B3 · A구역 · 142, 1시간 24분째 주차 중이에요.`
    /// - `B3 · A구역 · 142, 방금 주차했어요.`
    /// - `1시간 24분째 주차 중이에요. 층은 기록하지 않았어요.` when nothing was entered.
    static func text(for snapshot: ActiveParkingSnapshot?, now: Date) -> String {
        guard let snapshot else { return noParking }
        let place = ParkingEndProposalCopy.placeText(
            floor: snapshot.floorValue,
            zone: snapshot.zone,
            spot: snapshot.spot
        )
        let duration = ParkingElapsed.describeDuration(from: snapshot.startedAt, to: now)
        let when = duration == "방금" ? "방금 주차했어요." : "\(duration) 주차 중이에요."
        guard let place else { return "\(when) 층은 기록하지 않았어요." }
        return "\(place), \(when)"
    }
}
