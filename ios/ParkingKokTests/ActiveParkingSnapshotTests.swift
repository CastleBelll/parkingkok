import Foundation
import Testing
@testable import ParkingKok

/// **docs/06 §6 / §7a — the App Group projection and the delta stepper.**
///
/// These drive `FileActiveParkingSnapshotStore` over a scratch directory rather than a
/// real App Group container: the container is an entitlement, the contract is the file
/// protocol, and the file protocol is what two processes have to agree on.
struct ActiveParkingSnapshotTests {
    private static let now = TestTime.reference

    private func makeStore(_ directory: TemporaryWidgetDirectory) -> FileActiveParkingSnapshotStore {
        FileActiveParkingSnapshotStore(directory: directory.url)
    }

    @discardableResult
    private func publish(
        _ store: FileActiveParkingSnapshotStore,
        sessionId: UUID,
        floor: String? = "B3",
        zone: String? = "A구역",
        spot: String? = "142",
        at now: Date = ActiveParkingSnapshotTests.now
    ) -> Bool {
        store.publish(
            sessionId: sessionId,
            startedAt: Self.now,
            floor: floor.flatMap(FloorValue.parse),
            zone: zone,
            spot: spot,
            at: now
        )
    }

    // ── The projection ──────────────────────────────────────────────────────

    @Test("An absent projection reads as no active parking, not as a failure")
    func readsAbsentAsNil() {
        // Arrange — a container the app has never written to, which is every first launch.
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)

        // Act / Assert
        #expect(store.read() == nil)
    }

    @Test("Publishing writes the parking the widget draws, at revision 1")
    func publishesFirstRevision() throws {
        // Arrange
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        let sessionId = UUID()

        // Act
        let didChange = publish(store, sessionId: sessionId)

        // Assert
        #expect(didChange)
        let snapshot = try #require(store.read())
        #expect(snapshot.sessionId == sessionId)
        #expect(snapshot.revision == 1)
        #expect(snapshot.floorValue?.displayText == "B3")
        #expect(snapshot.placeText == "A구역 · 142")
        #expect(snapshot.version == ActiveParkingSnapshot.currentVersion)
    }

    @Test("Republishing an unchanged parking writes nothing and leaves the revision alone")
    func republishIsNotAMutation() throws {
        // Arrange — the app foregrounding twice with nothing having happened. docs/06 §5
        // increments the revision per *mutation*; a refresh is not one.
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        let sessionId = UUID()
        publish(store, sessionId: sessionId)

        // Act
        let didChange = publish(store, sessionId: sessionId, at: Self.now.addingTimeInterval(600))

        // Assert
        #expect(!didChange)
        #expect(try #require(store.read()).revision == 1)
    }

    @Test("Editing the parking in the app increments the revision")
    func publishIncrementsRevisionOnChange() throws {
        // Arrange
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        let sessionId = UUID()
        publish(store, sessionId: sessionId)

        // Act — the user renames the zone on the detail screen.
        let didChange = publish(store, sessionId: sessionId, zone: "B구역")

        // Assert
        #expect(didChange)
        let snapshot = try #require(store.read())
        #expect(snapshot.revision == 2)
        #expect(snapshot.placeText == "B구역 · 142")
    }

    @Test("A different parking starts its own revision count")
    func revisionIsPerSession() throws {
        // Arrange — one parking ended and another began; docs/06 §5 makes the revision
        // monotonic *within* a session, and the widget compares session ids, not revisions.
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        publish(store, sessionId: UUID())
        publish(store, sessionId: UUID(), floor: "2F")

        // Act / Assert
        #expect(try #require(store.read()).revision == 1)
    }

    @Test("Clearing removes the projection, and clearing again reports nothing to do")
    func clearsOnce() {
        // Arrange
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        publish(store, sessionId: UUID())

        // Act / Assert — the second clear must not ask for a widget reload.
        #expect(store.clear())
        #expect(store.read() == nil)
        #expect(!store.clear())
    }

    @Test("A projection from a future schema is discarded rather than guessed at")
    func rejectsUnknownVersion() throws {
        // Arrange — exactly what a downgrade leaves behind.
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        publish(store, sessionId: UUID())
        let url = directory.url.appending(path: "active-parking.json", directoryHint: .notDirectory)
        var raw = try #require(
            try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        )
        raw["version"] = ActiveParkingSnapshot.currentVersion + 1
        try JSONSerialization.data(withJSONObject: raw).write(to: url)

        // Act / Assert
        #expect(store.read() == nil)
    }

    @Test("Unreadable bytes read as no active parking instead of throwing at the widget")
    func toleratesCorruptFile() throws {
        // Arrange
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        publish(store, sessionId: UUID())
        let url = directory.url.appending(path: "active-parking.json", directoryHint: .notDirectory)
        try Data("not json".utf8).write(to: url)

        // Act / Assert
        #expect(store.read() == nil)
    }

    @Test("Nothing the widget cannot draw reaches the shared container")
    func projectionCarriesNoSensitiveFields() throws {
        // Arrange — CLAUDE.md forbids coordinates leaving the device and docs/09 forbids
        // the widget rendering a coordinate, an address or a photo. The projection is the
        // one file two processes can open, so the guard belongs on its actual bytes.
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        publish(store, sessionId: UUID())
        let url = directory.url.appending(path: "active-parking.json", directoryHint: .notDirectory)

        // Act
        let raw = try #require(
            try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        )

        // Assert
        #expect(Set(raw.keys) == [
            "version",
            "sessionId",
            "revision",
            "updatedAt",
            "startedAt",
            "floor",
            "zone",
            "spot"
        ])
    }

    // ── docs/06 §7a: the delta stepper ──────────────────────────────────────

    @Test("A step re-reads, applies the delta through the floor domain and bumps the revision")
    func stepAppliesDelta() throws {
        // Arrange
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        let sessionId = UUID()
        publish(store, sessionId: sessionId, floor: "B3")

        // Act
        let outcome = store.step(by: 1, expecting: sessionId, at: Self.now.addingTimeInterval(60))

        // Assert — B3 is level -3; one step up is B2.
        #expect(try outcome == .stepped(#require(FloorValue.parse("B2"))))
        let snapshot = try #require(store.read())
        #expect(snapshot.floorValue?.displayText == "B2")
        #expect(snapshot.revision == 2)
        #expect(snapshot.updatedAt == Self.now.addingTimeInterval(60))
    }

    @Test("Stepping up from B1 lands on 1F — the signed model has no zero")
    func stepCrossesGroundLevel() throws {
        // Arrange
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        let sessionId = UUID()
        publish(store, sessionId: sessionId, floor: "B1")

        // Act
        let outcome = store.step(by: 1, expecting: sessionId, at: Self.now)

        // Assert
        #expect(try outcome == .stepped(#require(FloorValue.parse("1F"))))
    }

    @Test("Two taps landing together move two floors, because the callback carries a delta")
    func concurrentTapsEachLand() async throws {
        // Arrange — docs/06 §7a: "Had the callback carried a value, the second write would
        // have silently discarded the first." Eight genuinely parallel taps, so the
        // read-modify-write really does interleave.
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        let sessionId = UUID()
        let taps = 8
        publish(store, sessionId: sessionId, floor: "B9")

        // Act
        await withCheckedContinuation { continuation in
            DispatchQueue.global().async {
                DispatchQueue.concurrentPerform(iterations: taps) { _ in
                    store.step(by: 1, expecting: sessionId, at: Self.now)
                }
                continuation.resume()
            }
        }

        // Assert — B9 up eight floors is B1, and every tap left a revision behind.
        let snapshot = try #require(store.read())
        #expect(snapshot.floorValue?.displayText == "B1")
        #expect(snapshot.revision == taps + 1)
    }

    @Test("Four rapid taps from B3 land on 2F — the same answer Android gives")
    func rapidTapsMatchAndroid() throws {
        // Arrange — CLAUDE.md "Cross-platform Behavioral Parity": the same input has to
        // produce the same product outcome on both platforms. The Android worker measured
        // B3 + four `+1` taps = 2F on a real device; this pins the iOS engine to it.
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        let sessionId = UUID()
        publish(store, sessionId: sessionId, floor: "B3")

        // Act — four separate taps, each carrying `+1`. Not one `stepped(by: 4)`: that
        // crosses the missing zero once instead of four times and lands on 1F, which is
        // exactly the bug the delta contract exists to prevent.
        for _ in 0 ..< 4 {
            store.step(by: 1, expecting: sessionId, at: Self.now)
        }

        // Assert — B3 → B2 → B1 → 1F (zero skipped) → 2F, with no tap lost.
        let snapshot = try #require(store.read())
        #expect(snapshot.floorValue?.displayText == "2F")
        #expect(snapshot.revision == 5)
    }

    @Test("A tap for a session that has ended is dropped, never applied to its successor")
    func stepDropsOnSessionMismatch() throws {
        // Arrange — the user ends the parking and starts a new one while the widget is
        // still showing the old tile (docs/06 §7a step 2).
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        let renderedSessionId = UUID()
        publish(store, sessionId: renderedSessionId, floor: "B3")
        publish(store, sessionId: UUID(), floor: "2F")

        // Act
        let outcome = store.step(by: 1, expecting: renderedSessionId, at: Self.now)

        // Assert — the successor is untouched.
        #expect(outcome == .dropped)
        let snapshot = try #require(store.read())
        #expect(snapshot.floorValue?.displayText == "2F")
        #expect(snapshot.revision == 1)
    }

    @Test("A tap with no parking at all is dropped")
    func stepDropsWithNoProjection() {
        // Arrange — the parking ended and the projection was cleared before the tap landed.
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)

        // Act / Assert
        #expect(store.step(by: 1, expecting: UUID(), at: Self.now) == .dropped)
    }

    @Test("A delta past the outermost floor is a no-op that changes nothing")
    func stepRejectsOutOfBounds() throws {
        // Arrange — `FloorValue.maximumNumber` is 999; there is no B1000.
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        let sessionId = UUID()
        publish(store, sessionId: sessionId, floor: "B999")

        // Act
        let outcome = store.step(by: -1, expecting: sessionId, at: Self.now.addingTimeInterval(60))

        // Assert — docs/06 §7a: rejected deltas leave the true value in place. The caller
        // still reloads, which is what snaps the display back.
        #expect(outcome == .rejected)
        let snapshot = try #require(store.read())
        #expect(snapshot.floorValue?.displayText == "B999")
        #expect(snapshot.revision == 1)
        #expect(snapshot.updatedAt == Self.now)
    }

    @Test("A floor that never parsed cannot be stepped", arguments: ["주차타워 2", "옥상"])
    func stepRejectsFreeText(_ floorText: String) throws {
        // Arrange — FR-005 allows `+`/`-` only on a numerically parsed floor.
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        let sessionId = UUID()
        publish(store, sessionId: sessionId, floor: floorText)

        // Act / Assert
        #expect(store.step(by: 1, expecting: sessionId, at: Self.now) == .rejected)
        #expect(try #require(store.read()).floorValue?.displayText == floorText)
    }

    @Test("A parking saved with no floor cannot be stepped into one")
    func stepRejectsMissingFloor() {
        // Arrange — FR-006 makes the floor optional, and a delta has nothing to apply to.
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        let sessionId = UUID()
        publish(store, sessionId: sessionId, floor: nil)

        // Act / Assert
        #expect(store.step(by: 1, expecting: sessionId, at: Self.now) == .rejected)
    }

    @Test("Zone and spot are shown only when they exist")
    func placeTextOmitsEmptyHalves() throws {
        // Arrange — docs/02 §9 makes both optional, so `A구역 · ` must never be drawn.
        let directory = TemporaryWidgetDirectory()
        let store = makeStore(directory)
        publish(store, sessionId: UUID(), zone: "A구역", spot: nil)
        #expect(try #require(store.read()).placeText == "A구역")

        publish(store, sessionId: UUID(), zone: nil, spot: "142")
        #expect(try #require(store.read()).placeText == "142")

        // Act / Assert — the manual sheet stores "" for a field the user left blank.
        publish(store, sessionId: UUID(), zone: "", spot: "")
        #expect(try #require(store.read()).placeText == nil)
    }
}

/// Scratch container per test, removed on deinit. Stands in for the App Group directory,
/// which is the same thing minus an entitlement.
final class TemporaryWidgetDirectory {
    let url: URL

    init() {
        url = FileManager.default.temporaryDirectory
            .appending(path: "pk-widget-\(UUID().uuidString)", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    deinit {
        try? FileManager.default.removeItem(at: url)
    }
}
