import CoreLocation
import Foundation
import Testing
@testable import ParkingPin

/// **FR-001, the hard constraint.**
///
/// CLAUDE.md: "Manual parking은 항상 가능해야 한다." These tests drive `ParkingModel`
/// with a location provider that behaves exactly as one does when location, motion and
/// notification permission have all been refused — it returns nothing — and prove the
/// save still completes and the record still reads back.
@MainActor
struct ManualParkingTests {
    private static let now = Date(timeIntervalSince1970: 1_700_000_000)
    private var now: Date {
        Self.now
    }

    private func makeModel(
        locationProvider: any ParkingLocationProviding = UnavailableParkingLocationProvider(),
        clock: any DateProviding = FixedDateProvider(ManualParkingTests.now),
        detection: (any ManualParkingReporting)? = nil
    ) throws -> ParkingModel {
        let store = try SwiftDataParkingStore(
            container: SwiftDataParkingStore.makeInMemoryContainer(),
            clock: clock
        )
        return ParkingModel(
            store: store,
            locationProvider: locationProvider,
            clock: clock,
            detection: detection
        )
    }

    /// Android's `SaveManualParkingUseCase` already refused this; iOS wrote the late fix
    /// onto the ended record (found in review, 2026-09-25).
    @Test("A fix that arrives after the parking was ended leaves the ended record alone")
    func lateFixSkipsEndedRecord() async throws {
        // Arrange
        let provider = LateFixProvider()
        let model = try makeModel(locationProvider: provider)
        #expect(await model.saveManualParking(ManualParkingDraft(floorText: "B2")))
        let id = try #require(model.activeSession?.id)
        await provider.waitUntilAsked()
        #expect(model.endActiveParking())

        // Act
        provider.answer(ParkedLocation(latitude: 37.5, longitude: 127.0, horizontalAccuracy: 9, capturedAt: now))
        for _ in 0 ..< 10 {
            await Task.yield()
        }

        // Assert
        model.refresh()
        #expect(model.session(id: id)?.location == nil)
    }

    @Test("With no permission of any kind, a manual parking saves and reads back")
    func savesWithEveryPermissionDenied() async throws {
        // Arrange — `UnavailableParkingLocationProvider` is the permission-less case:
        // no location manager, no prompt, nothing to return.
        let model = try makeModel()
        let draft = ManualParkingDraft(floorText: "B3", zone: "A구역", spot: "142", memo: "기둥 옆")

        // Act
        let saved = await model.saveManualParking(draft)

        // Assert — saved…
        #expect(saved)
        #expect(model.failure == nil)
        // …and readable, which is the half that makes the save worth anything.
        model.refresh()
        let active = try #require(model.activeSession)
        #expect(active.floor?.displayText == "B3")
        #expect(active.zone == "A구역")
        #expect(active.spot == "142")
        #expect(active.memo == "기둥 옆")
        #expect(active.source == .manual)
        #expect(active.startedAt == now)
        // The one thing permission would have added, and its absence is not a failure.
        #expect(active.location == nil)
    }

    @Test("A parking with nothing but a timestamp is still a valid parking")
    func savesEmptyDraft() async throws {
        // Arrange — the user taps 직접 저장 and saves without typing anything.
        let model = try makeModel()

        // Act
        let saved = await model.saveManualParking(ManualParkingDraft())

        // Assert
        #expect(saved)
        model.refresh()
        let active = try #require(model.activeSession)
        #expect(active.floor == nil)
        #expect(active.zone == nil)
        #expect(active.startedAt == now)
    }

    @Test("When a location is available it is attached, and it is still local-only")
    func attachesLocationWhenAvailable() async throws {
        // Arrange
        let fix = ParkedLocation(
            latitude: 37.123_456_7,
            longitude: 127.987_654_3,
            horizontalAccuracy: 18,
            capturedAt: now.addingTimeInterval(-60)
        )
        let model = try makeModel(locationProvider: StubParkingLocationProvider(location: fix))

        // Act
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "B3"))

        // Assert
        model.refresh()
        #expect(model.activeSession?.location == fix)
    }

    @Test("A denied second save is reported rather than silently ending the first parking")
    func reportsActiveConflict() async throws {
        // Arrange — FR-004's explicit conflict policy.
        let model = try makeModel()
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "B3"))
        let firstID = model.activeSession?.id

        // Act
        let saved = await model.saveManualParking(ManualParkingDraft(floorText: "B1"))

        // Assert
        #expect(!saved)
        #expect(model.failure != nil)
        #expect(model.activeSession?.id == firstID)
        #expect(model.activeSession?.floor?.displayText == "B3")
    }

    @Test("A manual save tells detection the car is parked, at the moment it was saved")
    func manualSaveReportsToDetection() async throws {
        // docs/05 §11c: without this the engine never reaches `PARKED` for a parking saved
        // by hand, and driving away from it ends nothing.
        // Arrange
        let detection = StubManualParkingReporter()
        let model = try makeModel(detection: detection)

        // Act
        let saved = await model.saveManualParking(ManualParkingDraft(floorText: "4F"))
        await model.detectionReport?.value

        // Assert
        #expect(saved)
        #expect(detection.savedAt == [now])
    }

    @Test("A save that failed tells detection nothing")
    func failedManualSaveReportsNothing() async throws {
        // Arrange — the second save is refused (FR-004), so there is no new parking to arm.
        let detection = StubManualParkingReporter()
        let model = try makeModel(detection: detection)
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "B3"))
        await model.detectionReport?.value

        // Act
        let saved = await model.saveManualParking(ManualParkingDraft(floorText: "B1"))
        await model.detectionReport?.value

        // Assert
        #expect(!saved)
        #expect(detection.savedAt.count == 1, "only the save that was written reaches the engine")
    }

    @Test("With no detection attached, a manual save still works")
    func manualSaveWithoutDetection() async throws {
        // CLAUDE.md: manual parking never depends on detection being present.
        let model = try makeModel(detection: nil)

        let saved = await model.saveManualParking(ManualParkingDraft(floorText: "B2"))

        #expect(saved)
        #expect(model.detectionReport == nil)
    }

    @Test("Ending the parking moves it into the history the home preview reads")
    func endingMovesRecordIntoHistory() async throws {
        // Arrange
        let clock = MutableDateProvider(now)
        let model = try makeModel(clock: clock)
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "B3"))
        clock.advance(by: 3600)

        // Act
        let ended = model.endActiveParking()

        // Assert
        #expect(ended)
        #expect(model.activeSession == nil)
        #expect(model.completedSessions.count == 1)
        #expect(model.completedSessions.first?.endedAt == clock.now)
        #expect(model.homePreviewSessions.count == 1)
    }

    @Test("The home preview is capped at three rows while history keeps everything")
    func capsHomePreviewOnly() async throws {
        // Arrange
        let clock = MutableDateProvider(now)
        let model = try makeModel(clock: clock)
        for index in 0 ..< 5 {
            _ = await model.saveManualParking(ManualParkingDraft(floorText: "B\(index + 1)"))
            clock.advance(by: 600)
            _ = model.endActiveParking()
            clock.advance(by: 600)
        }

        // Act
        model.refresh()

        // Assert — FR-009's free limit is not applied; only the preview is trimmed.
        #expect(model.completedSessions.count == 5)
        #expect(model.homePreviewSessions.count == ParkingModel.homePreviewLimit)
        #expect(model.homePreviewSessions.first?.floor?.displayText == "B5")
    }

    // ── The `-` / `+` keys ──────────────────────────────────────────────────

    @Test("Stepping the active floor persists, so a relaunch sees the new floor")
    func stepsActiveFloor() async throws {
        // Arrange
        let model = try makeModel()
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "B3"))

        // Act
        let stepped = model.stepActiveFloor(by: 1)

        // Assert
        #expect(stepped)
        #expect(model.activeSession?.floor?.displayText == "B2")
        // Re-read from the store rather than trusting the cached copy.
        model.refresh()
        #expect(model.activeSession?.floor?.displayText == "B2")
    }

    @Test("A free-text floor refuses to step — FR-005 allows it only on numeric floors")
    func refusesToStepFreeTextFloor() async throws {
        // Arrange
        let model = try makeModel()
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "옥상"))

        // Act / Assert
        #expect(!model.stepActiveFloor(by: 1))
        #expect(model.activeSession?.floor?.raw == "옥상")
    }

    @Test("Stepping with no parking at all is refused rather than crashing")
    func refusesToStepWithoutActiveParking() throws {
        // Arrange
        let model = try makeModel()

        // Act / Assert
        #expect(!model.stepActiveFloor(by: 1))
        #expect(!model.endActiveParking())
    }

    @Test("Deleting all local data clears the active parking and the history (docs/02 §15)")
    func deletesAllLocalData() async throws {
        // Arrange
        let clock = MutableDateProvider(now)
        let model = try makeModel(clock: clock)
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "B3"))
        clock.advance(by: 600)
        _ = model.endActiveParking()
        _ = await model.saveManualParking(ManualParkingDraft(floorText: "B1"))

        // Act
        let deleted = await model.deleteAllLocalData()

        // Assert
        #expect(deleted)
        #expect(model.activeSession == nil)
        #expect(model.completedSessions.isEmpty)
    }

    @Test("An empty draft is recognised as empty, whitespace and all")
    func detectsEmptyDraft() {
        #expect(ManualParkingDraft().isEmpty)
        #expect(ManualParkingDraft(floorText: "  ", zone: "\n").isEmpty)
        #expect(!ManualParkingDraft(zone: "A구역").isEmpty)
    }
}

/// A one-shot fix that arrives only when the test says so — the GPS answering late.
@MainActor
private final class LateFixProvider: ParkingLocationProviding {
    private var pending: CheckedContinuation<ParkedLocation?, Never>?
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func storedLocation() async -> ParkedLocation? {
        nil
    }

    func currentFix() async -> ParkedLocation? {
        await withCheckedContinuation { continuation in
            pending = continuation
            waiters.forEach { $0.resume() }
            waiters = []
        }
    }

    func waitUntilAsked() async {
        guard pending == nil else { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func answer(_ location: ParkedLocation) {
        pending?.resume(returning: location)
        pending = nil
    }
}

/// Hands back a fixed location, standing in for a granted-permission device.
@MainActor
private struct StubParkingLocationProvider: ParkingLocationProviding {
    let location: ParkedLocation?

    func storedLocation() async -> ParkedLocation? { location }
    func currentFix() async -> ParkedLocation? { nil }
}

/// A fake Core Location, so the order of `CurrentFixParkingLocationProvider` is testable
/// without a GPS.
@MainActor
private final class StubOneShotLocator: OneShotLocating {
    private let fix: CLLocation?
    private(set) var calls = 0
    private(set) var lastTimeout: TimeInterval?

    init(fix: CLLocation?) {
        self.fix = fix
    }

    func currentFix(timeout: TimeInterval) async -> CLLocation? {
        calls += 1
        lastTimeout = timeout
        return fix
    }
}

private func stubFix(accuracy: CLLocationAccuracy) -> CLLocation {
    CLLocation(
        coordinate: CLLocationCoordinate2D(latitude: 37.5, longitude: 127.0),
        altitude: 0,
        horizontalAccuracy: accuracy,
        verticalAccuracy: -1,
        timestamp: Date()
    )
}

/// **FR-001, and the bug that made this suite exist.** 주차 위치 저장 saved no location on a
/// phone that had not driven with detection on, because the provider only ever read the
/// detection checkpoint and never asked the OS.
@MainActor
struct CurrentFixParkingLocationProviderTests {
    /// Measured on an iPhone on 2026-09-24: a 10 m fix arrived 10 s after the save, 2 s
    /// past the old 8 s deadline, and the record kept no location.
    @Test("The fix is given longer than a cold fix measured on a real phone takes")
    func deadlineOutlastsAMeasuredColdFix() async {
        // Arrange
        let measuredColdFix: TimeInterval = 10
        let locator = StubOneShotLocator(fix: stubFix(accuracy: 10))
        let provider = CurrentFixParkingLocationProvider(
            locator: locator,
            fallback: UnavailableParkingLocationProvider()
        )

        // Act
        _ = await provider.currentFix()

        // Assert
        #expect((locator.lastTimeout ?? 0) > measuredColdFix)
    }

    @Test("The fix comes from the OS, asked for after the record already exists")
    func asksForAFix() async {
        let locator = StubOneShotLocator(fix: stubFix(accuracy: 12))
        let provider = CurrentFixParkingLocationProvider(
            locator: locator,
            fallback: UnavailableParkingLocationProvider()
        )

        let location = await provider.currentFix()

        #expect(locator.calls == 1)
        #expect(location?.horizontalAccuracy == 12)
    }

    @Test("The save path never waits for the GPS")
    func storedLocationDoesNotAskTheOS() async {
        // The whole reason the protocol has two methods: eight silent seconds on a disabled
        // button is what "저장 눌러도 반응 없다" was.
        let locator = StubOneShotLocator(fix: stubFix(accuracy: 12))
        let provider = CurrentFixParkingLocationProvider(
            locator: locator,
            fallback: UnavailableParkingLocationProvider()
        )

        _ = await provider.storedLocation()

        #expect(locator.calls == 0)
    }

    @Test("A fix too coarse to mean anything is not a fix at all")
    func coarseFixIsRejected() async {
        // 2 km is what an indoor fix reports, and a pin drawn from it would claim a place
        // the car is not (FR-008).
        let fallbackLocation = ParkedLocation(
            latitude: 37.6,
            longitude: 127.1,
            horizontalAccuracy: 20,
            capturedAt: Date()
        )
        let provider = CurrentFixParkingLocationProvider(
            locator: StubOneShotLocator(fix: stubFix(accuracy: 2000)),
            fallback: StubParkingLocationProvider(location: fallbackLocation)
        )

        // Nothing to attach, so the record keeps whatever the save already stored.
        #expect(await provider.currentFix() == nil)
    }

    @Test("No fix and no checkpoint still saves, without coordinates")
    func noFixIsANormalAnswer() async {
        // FR-001: 위치 권한 없이도 저장 가능. Both halves answer nil and the record is
        // written regardless.
        let provider = CurrentFixParkingLocationProvider(
            locator: StubOneShotLocator(fix: nil),
            fallback: UnavailableParkingLocationProvider()
        )

        #expect(await provider.storedLocation() == nil)
        #expect(await provider.currentFix() == nil)
    }
}

/// The stale-fix rule on the real provider: an old location is not where the car is.
@MainActor
struct ParkingLocationProviderTests {
    @Test("The permission-less provider returns nothing, which is a normal answer")
    func unavailableProviderReturnsNil() async {
        #expect(await UnavailableParkingLocationProvider().storedLocation() == nil)
    }

    @Test("The freshness window is the one the detection stack's capture cadence implies")
    func declaresFreshnessWindow() {
        // A fix older than this is wherever the phone was, not where the car is
        // (FR-008 forbids presenting that as the parking spot).
        #expect(DetectionParkingLocationProvider.maximumAge == 15 * 60)
    }
}
