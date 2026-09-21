import Foundation

/// Where a manual save gets a coordinate from, when there is one to get.
///
/// **FR-001 is the whole reason this is a protocol.** "위치 권한 없이도 저장 가능" means
/// the save path may never depend on this succeeding: a `nil` here is an ordinary
/// outcome, not an error, and the record is written without a location. Permission
/// denied, Smart Detection off, a fresh install that has never seen a fix — all `nil`.
@MainActor
protocol ParkingLocationProviding: Sendable {
    /// Best-effort, and never prompts. Returns `nil` whenever there is no location the
    /// app can honestly attach to a parking.
    func currentParkedLocation() async -> ParkedLocation?
}

/// Reads whatever the detection stack last trusted (docs/05 §7 `lastReliableLocation`).
///
/// Deliberately a reader, not a requester: it does not start a location session and does
/// not ask for permission. Manual save is the path that has to work when everything else
/// is switched off, so it takes what already exists or nothing at all.
@MainActor
struct DetectionParkingLocationProvider: ParkingLocationProviding {
    /// How old a stored fix may be and still describe where the car is.
    ///
    /// The last reliable location is captured when driving stops, so a fresh one is the
    /// car park entrance. An hour-old one is wherever the phone was an hour ago, and
    /// pinning a parking to it would be the "exact car location" claim FR-008 forbids.
    static let maximumAge: TimeInterval = 15 * 60

    private let runtime: DetectionRuntime
    private let clock: any DateProviding

    init(runtime: DetectionRuntime = .shared, clock: any DateProviding = SystemDateProvider()) {
        self.runtime = runtime
        self.clock = clock
    }

    func currentParkedLocation() async -> ParkedLocation? {
        guard let reliable = await runtime.snapshot().currentCheckpoint?.lastReliableLocation else {
            return nil
        }
        guard clock.now.timeIntervalSince(reliable.capturedAt) <= Self.maximumAge else {
            return nil
        }
        return ParkedLocation(reliable)
    }
}

/// The permission-less case, made explicit.
///
/// Used by previews and by the FR-001 tests, which have to prove that a save with no
/// location whatsoever still round-trips.
@MainActor
struct UnavailableParkingLocationProvider: ParkingLocationProviding {
    func currentParkedLocation() async -> ParkedLocation? {
        nil
    }
}
