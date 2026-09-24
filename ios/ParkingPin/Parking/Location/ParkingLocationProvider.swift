import Foundation

/// Where a manual save gets a coordinate from, when there is one to get.
///
/// **FR-001 is the whole reason this is a protocol.** "위치 권한 없이도 저장 가능" means
/// the save path may never depend on this succeeding: a `nil` here is an ordinary
/// outcome, not an error, and the record is written without a location. Permission
/// denied, Smart Detection off, a fresh install that has never seen a fix — all `nil`.
@MainActor
protocol ParkingLocationProviding: Sendable {
    /// What is already known, without waiting for anything. Returns immediately.
    ///
    /// This is what a save uses, because a save must not wait: the record is local and the
    /// user is standing next to their car.
    func storedLocation() async -> ParkedLocation?

    /// One fix from the OS, which takes as long as a GPS takes.
    ///
    /// Never prompts (FR-001), and `nil` is an ordinary answer — no authorization, a
    /// timeout, indoors. Called *after* a record exists, never before it.
    func currentFix() async -> ParkedLocation?
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

    func storedLocation() async -> ParkedLocation? {
        let checkpoint = await runtime.snapshot().currentCheckpoint
        guard let reliable = checkpoint?.lastReliableLocation else {
            #if PK_DEV
                SaveLocationDiagnostics.note("stored", checkpoint == nil ? "noCheckpoint" : "noReliable")
            #endif
            return nil
        }
        let age = clock.now.timeIntervalSince(reliable.capturedAt)
        #if PK_DEV
            let detail = "age=\(Int(age))s accuracy=\(Int(reliable.horizontalAccuracy))m"
            SaveLocationDiagnostics.note("stored", age <= Self.maximumAge ? "used \(detail)" : "tooOld \(detail)")
        #endif
        guard age <= Self.maximumAge else { return nil }
        return ParkedLocation(reliable)
    }

    /// The detection stack does not hold a live GPS open, and a save must not open one
    /// either — [CurrentFixParkingLocationProvider] is the half that asks.
    func currentFix() async -> ParkedLocation? { nil }
}

/// Asks the OS for a fix, because the user just pressed 주차 위치 저장 (FR-001).
///
/// **The old behaviour was to read the detection checkpoint and nothing else**, which meant
/// a button named "save parking location" saved no location at all on a phone that had not
/// driven with detection on — a fresh install, detection switched on this morning, a trip
/// taken before the opt-in. Reported from the device, and it is the app's whole point
/// missing from the app's main button.
///
/// The reasoning behind the old shape conflated two rules. FR-001's "위치 권한 없이도 저장
/// 가능" means the save must survive a refusal; it does not mean the app may never ask. And
/// pressing this button is the clearest location request a user can make — clearer than the
/// background authorization the detection opt-in asks for.
///
/// Order, and why:
/// 1. **A fix now.** The car is here, at this moment. Nothing else can say that.
/// 2. **The checkpoint**, if it is fresh enough — underground, where a one-shot fix will not
///    come, the drive that just ended is the better answer anyway.
/// 3. **Nothing**, and the record is saved without coordinates. FR-001 intact.
@MainActor
struct CurrentFixParkingLocationProvider: ParkingLocationProviding {
    /// A save is not a drive: the engine's 35 m gate (docs/05 §6) exists to keep a poor
    /// sample from becoming `lastReliableLocation` mid-drive, where a better one is a second
    /// away. Here there is no second fix coming, and a 60 m pin still answers "which
    /// building". Past 100 m it stops answering anything, and FR-008 forbids presenting that
    /// as where the car is.
    static let maximumHorizontalAccuracy: Double = 100

    private let locator: any OneShotLocating
    private let fallback: any ParkingLocationProviding

    init(
        locator: any OneShotLocating = CoreLocationOneShotLocator(),
        fallback: any ParkingLocationProviding = DetectionParkingLocationProvider()
    ) {
        self.locator = locator
        self.fallback = fallback
    }

    func storedLocation() async -> ParkedLocation? {
        await fallback.storedLocation()
    }

    func currentFix() async -> ParkedLocation? {
        guard let fix = await locator.currentFix(timeout: CoreLocationOneShotLocator.defaultTimeout),
              fix.horizontalAccuracy > 0,
              fix.horizontalAccuracy <= Self.maximumHorizontalAccuracy
        else { return nil }
        #if PK_DEV
            SaveLocationDiagnostics.note("fix", "ok accuracy=\(Int(fix.horizontalAccuracy))m")
        #endif
        return ParkedLocation(
            latitude: fix.coordinate.latitude,
            longitude: fix.coordinate.longitude,
            horizontalAccuracy: fix.horizontalAccuracy,
            capturedAt: fix.timestamp
        )
    }
}

/// The permission-less case, made explicit.
///
/// Used by previews and by the FR-001 tests, which have to prove that a save with no
/// location whatsoever still round-trips.
@MainActor
struct UnavailableParkingLocationProvider: ParkingLocationProviding {
    func storedLocation() async -> ParkedLocation? { nil }
    func currentFix() async -> ParkedLocation? { nil }
}
