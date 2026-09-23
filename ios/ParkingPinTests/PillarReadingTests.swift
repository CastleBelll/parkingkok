import Foundation
import Testing
import Vision
@testable import ParkingPin

/// docs/02 §6a: what a pillar photo may offer, what it must never do with it, and how
/// loudly it is allowed to fail (not at all).
@Suite("Pillar reading")
struct PillarFloorSuggestionTests {
    @Test(
        "The floor comes from docs/02 §6's own parser",
        arguments: [
            ("B3", "B3"),
            ("b 3", "b 3"),
            ("지하 3층", "지하 3층"),
            ("지하3", "지하3"),
            ("3F", "3F"),
            ("3층", "3층"),
            ("지상 2층", "지상 2층")
        ]
    )
    func acceptsTheParsersSpellings(input: String, expected: String) {
        // Arrange / Act
        let suggestion = PillarFloorSuggestion.floorText(fromLines: [input])

        // Assert — the raw text is kept, per §6 "preserve raw text".
        #expect(suggestion == expected)
        #expect(FloorValue.parse(input)?.kind != .freeText)
    }

    @Test("A painted row is searched word by word")
    func findsTheFloorInsideALine() {
        // Arrange — one observation, the way a pillar paints it.
        let lines = ["B3 A구역 142"]

        // Act / Assert
        #expect(PillarFloorSuggestion.floorText(fromLines: lines) == "B3")
    }

    @Test(
        "A bare number is a bay, never a floor",
        arguments: ["142", "83", "07", "3"]
    )
    func refusesBareNumbers(input: String) {
        // Arrange — the guard that belongs to this caller: `FloorValue.parse` reads a
        // bare `142` as the 142nd floor, which is right for a field labelled 층 and
        // wrong for a number painted on a wall.
        #expect(FloorValue.parse(input)?.kind == .ground)

        // Act / Assert
        #expect(PillarFloorSuggestion.floorText(fromLines: [input]) == nil)
    }

    @Test("The parser itself is untouched — a typed bare number is still a floor")
    func parserIsNotWeakened() {
        // Assert — FR-005, the widget stepper and the manual sheet all still accept `3`.
        let parsed = FloorValue.parse("3")
        #expect(parsed?.kind == .ground)
        #expect(parsed?.number == 3)
        #expect(parsed?.isSteppable == true)
    }

    @Test(
        "Text that parses to nothing offers nothing",
        arguments: [[String](), [""], ["EXIT"], ["주차장"], ["A구역"], ["   "]]
    )
    func offersNothingWhenNothingParses(lines: [String]) {
        // Act / Assert — §6a: "no text found, or nothing parses → the form opens exactly
        // as it does today, empty".
        #expect(PillarFloorSuggestion.floorText(fromLines: lines) == nil)
    }

    @Test("A pillar number is not a floor, however confidently it is read")
    func pillarNumberIsNotAFloor() {
        // The real read of a real B2 garage whose pillars are numbered B14–B17, verbatim
        // from Vision, including what it made of the neighbouring pillar's badge:
        let lines = ["C13 1", "B2", "B17", "B", "82", "B16", "BB15"]

        // `B17` came back with confidence 1.00 and `B2` with 0.30, and on the phone `B17`
        // came first — which is how 지하 17층 was offered for a car parked on B2. Order and
        // confidence are both the wrong signal; the number's size is the right one.
        #expect(PillarFloorSuggestion.floorText(fromLines: lines) == "B2")
    }

    @Test(
        "Nothing deeper than a garage goes is offered",
        arguments: ["B17", "B11", "지하 15층", "82", "82F"]
    )
    func implausibleFloorsAreNotOffered(input: String) {
        // Offering one is worse than offering nothing: the user has to notice it and undo
        // it, and a wrong floor is exactly what the read was supposed to save them from.
        #expect(PillarFloorSuggestion.floorText(fromLines: [input]) == nil)
    }

    @Test(
        "The floors a garage actually has are still offered",
        arguments: ["B1", "B7", "B10", "지하 3층", "2F", "20F"]
    )
    func plausibleFloorsSurvive(input: String) {
        #expect(PillarFloorSuggestion.floorText(fromLines: [input]) != nil)
    }

    @Test("A badge whose B was read as an 8 is still the floor, when it repeats")
    func repeatedBadgeIsTheFloor() {
        // What the phone actually read of the B2 garage — `B2` never appeared, `82` did,
        // four times, once per pillar in frame.
        let lines = ["10/ C13", "a", "82", "B17", "B", "82", "B16", "[", "82", "B B15", "814", "82"]

        #expect(PillarFloorSuggestion.floorText(fromLines: lines) == "B2")
    }

    @Test("A bay number that appears once is still a bay number")
    func loneDigitsAreNotAFloor() {
        // The rule that keeps the correction honest: without repetition, `82` on a pillar
        // is the bay it almost always is, and rewriting it would invent a floor.
        #expect(PillarFloorSuggestion.floorText(fromLines: ["82", "A구역"]) == nil)
    }

    @Test("A read that already says the floor does not need correcting")
    func plainBadgeWinsOverCorrection() {
        // Close up, the badge reads properly; the correction is for the wide shot only.
        #expect(PillarFloorSuggestion.floorText(fromLines: ["B2", "82", "82"]) == "B2")
    }

    @Test("The zone and the bay are read too, when the wall says them")
    func zoneAndSpotAreRead() {
        // §6a's opening sentence: a pillar carries "the floor, the zone and often the bay
        // number". iOS read only the floor until 2026-09-23, while Android read all three.
        let (zone, spot) = PillarFloorSuggestion.zoneAndSpot(
            fromLines: ["B3", "A구역", "142번"],
            excluding: ["B3"]
        )

        #expect(zone == "A구역")
        #expect(spot == "142")
    }

    @Test("The digits the floor correction used are not also offered as the bay")
    func correctedBadgeIsNotABay() {
        // The wide shot again: `82` is the badge read badly, and offering it as bay 82
        // would hand the user the same misread twice.
        let (_, spot) = PillarFloorSuggestion.zoneAndSpot(
            fromLines: ["82", "B17", "82", "82"],
            excluding: ["B2", "82"]
        )

        #expect(spot == nil)
    }

    @Test("The pillar's own number is the zone, when the photo says which pillar")
    func lonePillarLabelIsTheZone() {
        // A close-up of the pillar the car is at: one label, and it is what the user would
        // write down.
        let (zone, _) = PillarFloorSuggestion.zoneAndSpot(fromLines: ["B2", "B17"], excluding: ["B2"])

        #expect(zone == "B17")
    }

    @Test("A frame full of pillars picks the nearest one")
    func nearestPillarWins() {
        // The wide shot, with the glyph heights the phone actually measured: the pillar the
        // car is at is the one nearest the camera, and the nearest is painted largest.
        let (zone, _) = PillarFloorSuggestion.zoneAndSpot(
            fromLines: ["82", "B17", "82", "B16", "82", "B15", "82"],
            excluding: ["B2", "82"],
            heights: ["B17": 0.061, "B16": 0.044, "B15": 0.050]
        )

        #expect(zone == "B17")
    }

    @Test("Two pillars the same size are two pillars the photo cannot choose between")
    func equallyDistantPillarsAreAmbiguous() {
        let (zone, _) = PillarFloorSuggestion.zoneAndSpot(
            fromLines: ["B2", "B17", "B16"],
            excluding: ["B2"],
            heights: ["B17": 0.050, "B16": 0.049]
        )

        #expect(zone == nil)
    }

    @Test("With no sizes to compare, several pillars name none")
    func noHeightsMeansNoGuess() {
        // Every fake reader in these tests hands over text and no geometry, which is the
        // honest shape of "we do not know how big it was".
        let (zone, _) = PillarFloorSuggestion.zoneAndSpot(
            fromLines: ["B2", "B17", "B16"],
            excluding: ["B2"]
        )

        #expect(zone == nil)
    }

    @Test("A pillar label whose B was read as an 8 is not offered as the bay")
    func misreadLabelIsNotABay() {
        // `814` in a photo that also shows B15, B16 and B17 is `B14`. It was reaching the
        // 자리 field as bay 814.
        let (_, spot) = PillarFloorSuggestion.zoneAndSpot(
            fromLines: ["82", "B17", "B16", "B15", "814"],
            excluding: ["B2", "82"],
            heights: ["B17": 0.061, "B16": 0.044, "B15": 0.050]
        )

        #expect(spot == nil)
    }

    @Test("A bay number on a wall with no such labels is still the bay")
    func realBayNumberSurvives() {
        // The rule is about *this* wall's shapes: with nothing shaped like `B14` in frame,
        // `814` is what it looks like.
        let (_, spot) = PillarFloorSuggestion.zoneAndSpot(
            fromLines: ["B3", "A구역", "814"],
            excluding: ["B3"]
        )

        #expect(spot == "814")
    }

    @Test("A wall with no 구역 on it offers no zone")
    func zoneNeedsItsWord() {
        // Without the literal word, every two-character token on a wall of signage is a
        // zone. §6a's "leave the rest blank" is the better answer than a guess.
        let (zone, _) = PillarFloorSuggestion.zoneAndSpot(
            fromLines: ["B3", "C13", "D14"],
            excluding: ["B3"]
        )

        // Two pillar labels and no 구역: nothing is answerable.
        #expect(zone == nil)
    }

    @Test("The first floor-shaped line wins")
    func firstMatchWins() {
        // Arrange — a pillar paints the floor above the bay far more often than below.
        let lines = ["지하 3층", "B4"]

        // Act / Assert
        #expect(PillarFloorSuggestion.floorText(fromLines: lines) == "지하 3층")
    }
}

@Suite("Pillar suggestion is only ever a suggestion")
struct PillarSuggestionTests {
    @Test("An empty field takes the suggestion")
    func fillsAnEmptyField() {
        // Arrange
        let reading = PillarReading(floorText: "B3")

        // Act / Assert
        #expect(reading.suggestedFloorText(over: "") == "B3")
        #expect(reading.suggestedFloorText(over: "   ") == "B3")
        #expect(reading.draft.floorText == "B3")
    }

    @Test("A field the user already filled is never overwritten")
    func neverOverwrites() {
        // Arrange — §6a fills "the fields the user was going to fill anyway"; a field
        // that is already filled is not one of them.
        let reading = PillarReading(floorText: "83")

        // Act / Assert
        #expect(reading.suggestedFloorText(over: "B3") == nil)
    }

    @Test("A failed read changes nothing and says nothing")
    func failedReadIsSilent() {
        // Arrange
        let reading = PillarReading.none

        // Act / Assert — the form opens byte for byte as it does today.
        #expect(reading.isEmpty)
        #expect(reading.floorText == nil)
        #expect(reading.suggestedFloorText(over: "") == nil)
        #expect(reading.draft == ManualParkingDraft())
        #expect(reading.draft.isEmpty)
    }
}

/// The half of §6a that has to be true against the real store: a suggestion is text in a
/// field and nothing else until the user presses 저장.
@MainActor
@Suite("A pillar reading is never auto-saved")
struct PillarAutoSaveTests {
    @Test("A read floor becomes a record only when the user confirms")
    func nothingIsSavedUntilConfirmed() async throws {
        // Arrange
        let clock = MutableDateProvider(TestTime.reference)
        let parkingStore = try SwiftDataParkingStore(
            container: SwiftDataParkingStore.makeInMemoryContainer(),
            clock: clock
        )
        let parking = ParkingModel(store: parkingStore, clock: clock)
        parking.refresh()
        let candidate = TestCandidate.make(detectedAt: TestTime.reference)
        let history = StubCandidateHistoryStore()
        let candidates = CandidateModel(
            store: StubParkingCandidateStore(current: candidate),
            history: history,
            notifier: StubCandidateNotifier(),
            parking: parking,
            clock: clock,
            resolver: StubCandidateResolver()
        )
        candidates.refresh()

        // Act — the read happens, and that is all it does.
        let reading = await StubPillarTextReader(floorText: "B3").read(Data())

        // Assert — §6a: "a misread `B3` as `83` that silently became the record would be
        // worse than typing".
        #expect(reading.floorText == "B3")
        #expect(parking.activeSession == nil)
        #expect(parking.completedSessions.isEmpty)
        #expect(candidates.pending?.id == candidate.id)
        #expect(history.entries().isEmpty)

        // Act — now the user presses 저장 on the pre-filled form.
        #expect(candidates.confirm(candidate, draft: reading.draft))

        // Assert
        #expect(parking.activeSession?.floor?.displayText == "B3")
    }

    @Test("A read that found nothing saves nothing and leaves the candidate pending")
    func silentFailureLeavesEverythingAlone() async throws {
        // Arrange
        let clock = MutableDateProvider(TestTime.reference)
        let parkingStore = try SwiftDataParkingStore(
            container: SwiftDataParkingStore.makeInMemoryContainer(),
            clock: clock
        )
        let parking = ParkingModel(store: parkingStore, clock: clock)
        parking.refresh()
        let candidate = TestCandidate.make(detectedAt: TestTime.reference)
        let candidates = CandidateModel(
            store: StubParkingCandidateStore(current: candidate),
            history: StubCandidateHistoryStore(),
            notifier: StubCandidateNotifier(),
            parking: parking,
            clock: clock,
            resolver: StubCandidateResolver()
        )
        candidates.refresh()

        // Act
        let reading = await StubPillarTextReader().read(Data())

        // Assert
        #expect(reading == .none)
        #expect(parking.activeSession == nil)
        #expect(candidates.pending?.id == candidate.id)
        #expect(parking.failure == nil)
    }
}

/// The Vision reader itself, on synthetic pillars.
///
/// These run against the real framework rather than a stub: §6a's requirement is that a
/// Korean wall can be read **on device with no network**, and a stub cannot say whether
/// the model is there.
@Suite("Vision pillar reader")
struct VisionPillarTextReaderTests {
    @Test("Korean is a supported recognition language at the level the reader uses")
    func koreanIsSupported() {
        // Arrange — `.fast` recognises six Latin languages and no Korean at all, so this
        // is the assertion that stops someone "optimising" the level later.
        var accurate = RecognizeTextRequest()
        accurate.recognitionLevel = .accurate
        var fast = RecognizeTextRequest()
        fast.recognitionLevel = .fast

        // Act
        let accurateIds: [String] = accurate.supportedRecognitionLanguages.map(\.maximalIdentifier)
        let fastIds: [String] = fast.supportedRecognitionLanguages.map(\.maximalIdentifier)

        // Assert
        #expect(accurateIds.contains { $0.hasPrefix("ko") })
        #expect(!fastIds.contains { $0.hasPrefix("ko") })
        let readerIds: [String] = VisionPillarTextReader.languages.map(\.maximalIdentifier)
        #expect(readerIds.contains { $0.hasPrefix("ko") })
    }

    @Test("A Latin pillar reads as the floor it paints")
    func readsLatinPillar() async throws {
        // Arrange
        let data = try #require(TestPillarImage.jpeg(text: "B3"))

        // Act
        let reading = await VisionPillarTextReader().read(data)

        // Assert
        #expect(reading.floorText == "B3")
    }

    @Test("A Korean pillar reads as the floor it paints")
    func readsKoreanPillar() async throws {
        // Arrange
        let data = try #require(TestPillarImage.jpeg(text: "지하 3층"))

        // Act
        let reading = await VisionPillarTextReader().read(data)

        // Assert — the whole reason the reader is pinned to `.accurate`.
        #expect(FloorValue.parse(reading.floorText ?? "")?.kind == .basement)
        #expect(FloorValue.parse(reading.floorText ?? "")?.number == 3)
    }

    @Test("A bay number alone is the bay, never the floor")
    func doesNotOfferABayNumber() async throws {
        // Arrange
        let data = try #require(TestPillarImage.jpeg(text: "142"))

        // Act
        let reading = await VisionPillarTextReader().read(data)

        // Assert — `FloorValue.parse` would read `142` as the 142nd storey, which is the
        // right answer for someone typing into a field labelled 층 and the wrong one for a
        // number painted on a wall. §6a puts it where it belongs instead.
        #expect(reading.floorText == nil)
        #expect(reading.spot == "142")
    }

    @Test("Preparing leaves the reader usable, and repeating it is harmless")
    func prepareIsSafeAndIdempotent() async throws {
        // Arrange — the model load moved off the path the user waits on.
        let reader = VisionPillarTextReader()
        let data = try #require(TestPillarImage.jpeg(text: "B3"))

        // Act
        await reader.prepare()
        await reader.prepare()

        // Assert — nothing thrown, nothing broken, and the read that follows still reads.
        #expect(await reader.read(data).floorText == "B3")
    }

    @Test("A reader that does not need preparing still conforms")
    func prepareIsOptional() async {
        // Arrange — `StubPillarTextReader` implements `read` and nothing else, which only
        // compiles because the protocol's `prepare` has a default.
        let stub = StubPillarTextReader(floorText: "B3")

        // Act
        await stub.prepare()

        // Assert
        #expect(await stub.read(Data()).floorText == "B3")
    }

    @Test("Bytes that are not an image fail silently")
    func garbageIsSilent() async {
        // Arrange — §6a: "the model is unavailable or takes too long → same as no text
        // found". Nothing throws, nothing is said.
        let data = Data("not an image".utf8)

        // Act
        let reading = await VisionPillarTextReader().read(data)

        // Assert
        #expect(reading == .none)
    }

    @Test("A deadline that has already passed reads as nothing, not as an error")
    func timeoutIsSilent() async throws {
        // Arrange
        let data = try #require(TestPillarImage.jpeg(text: "B3"))

        // Act — a deadline no real read can meet.
        let reading = await VisionPillarTextReader(timeout: .nanoseconds(1)).read(data)

        // Assert
        #expect(reading == .none)
    }
}
