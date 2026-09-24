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

    @Test("A zone is read with or without the space before 구역")
    func spacedZone() {
        // docs/02 §6a's table accepts both spellings, and Android read both from the day
        // the feature landed. Split on whitespace, `A 구역` is just `A` — which the
        // lone-letter rule would now offer as the zone, losing the 구역 on the wall.
        #expect(PillarFloorSuggestion.zoneAndSpot(fromLines: ["A구역"], excluding: []).0 == "A구역")
        #expect(PillarFloorSuggestion.zoneAndSpot(fromLines: ["A 구역"], excluding: []).0 == "A구역")
    }

    @Test("A pillar that paints its letter above its number keeps the letter")
    func letterRowIsTheZone() {
        let (zone, spot) = PillarFloorSuggestion.zoneAndSpot(
            fromLines: ["A", "47"],
            excluding: [],
            heights: ["A": 0.140, "47": 0.143]
        )

        #expect(zone == "A")
        #expect(spot == "47")
    }

    @Test("A stray letter beside real pillar labels is not a zone of its own")
    func strayLetterIsNotAZone() {
        // The wide shot returns `B` on its own beside `B17` and `B16`: a fragment of a
        // label already read, not a pillar the car could be at.
        let (zone, _) = PillarFloorSuggestion.zoneAndSpot(
            fromLines: ["B", "B17", "B16"],
            excluding: [],
            heights: ["B17": 0.050, "B16": 0.049]
        )

        #expect(zone == nil)
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

/// Twelve real pillar photos, read by Vision on this machine, with what the wall actually
/// said beside what the recogniser returned.
///
/// Every rule in `PillarFloorSuggestion` was written against a photo, and this is the set
/// of photos. Perfect reading is not achievable — §6a says so, and four of these twelve
/// still lose something — but a rule that fixes one wall and breaks another is caught here
/// rather than on the phone. The rows are the recogniser's verbatim output, `text|height`,
/// copied from the diagnostics dump; the height is the fraction of the image the row filled.
///
/// The same twelve are asserted on Android in `PillarTextParserTest`.
@Suite("Real pillar photos")
struct PillarPhotoFixtureTests {
    /// A close-up of a single B1 wall, where the badge's `B` came back as an `8`.
    ///
    /// The repetition rule cannot fire on one pillar. Size is what is left, and `81` filled
    /// an eighth of the frame — this is the photo `largeBadgeHeight` exists for. Before it,
    /// the user was offered 자리 81 for a car on B1.
    @Test("7B4E5633 — a lone 81 painted across the frame is B1")
    func loneLargeBadgeIsTheFloor() {
        let reading = PillarFloorSuggestion.reading(from: [PillarLine("81", height: 0.129)])

        #expect(reading.floorText == "B1")
        #expect(reading.spot == nil)
    }

    /// A pillar that paints its letter above its number, which Vision returned as two rows.
    @Test("77F9B13C — A over 47 is zone A, bay 47")
    func letterAndNumberOnSeparateRows() {
        let reading = PillarFloorSuggestion.reading(from: [
            PillarLine("A", height: 0.140),
            PillarLine("47", height: 0.143)
        ])

        #expect(reading.floorText == nil)
        #expect(reading.zone == "A")
        #expect(reading.spot == "47")
    }

    /// Three bays in one frame — `02` in front of the camera, `03` and `04` down the row.
    @Test("98E8104E — the nearest bay wins, not the first one read")
    func nearestBayWins() {
        let reading = PillarFloorSuggestion.reading(from: [
            PillarLine("04", height: 0.031),
            PillarLine("03", height: 0.047),
            PillarLine("B2", height: 0.031),
            PillarLine("02 02", height: 0.136),
            PillarLine("B2", height: 0.074),
            PillarLine("B2", height: 0.082)
        ])

        #expect(reading.floorText == "B2")
        #expect(reading.spot == "02")
    }

    /// The wide shot the earlier rules were written against, unchanged by the new ones.
    @Test("41EECF59 — a repeated 82 is the floor and the nearest pillar is the zone")
    func wideShotOfALabelledRow() {
        let reading = PillarFloorSuggestion.reading(from: [
            PillarLine("10/ C13", height: 0.031),
            PillarLine("a", height: 0.027),
            PillarLine("82", height: 0.023),
            PillarLine("B17", height: 0.059),
            PillarLine("B", height: 0.047),
            PillarLine("82", height: 0.020),
            PillarLine("B16", height: 0.047),
            PillarLine("[", height: 0.031),
            PillarLine("82", height: 0.020),
            PillarLine("B B15", height: 0.051),
            PillarLine("814", height: 0.043),
            PillarLine("82", height: 0.027)
        ])

        #expect(reading.floorText == "B2")
        // The stray `B` is a fragment of a label already read, not a zone of its own.
        #expect(reading.zone == "B17")
        // `814` is `B14`, and `B14` is not a floor either.
        #expect(reading.spot == nil)
    }

    @Test("4BC91EF8 — B2 over bay 02, with a warning sign in frame")
    func floorAboveBayWithSignage() {
        let reading = PillarFloorSuggestion.reading(from: [
            PillarLine("주차장 바닥에 브레거를", height: 0.014),
            PillarLine("버려지 마셔요.", height: 0.012),
            PillarLine("B2", height: 0.152),
            PillarLine("B2 02", height: 0.063)
        ])

        #expect(reading.floorText == "B2")
        #expect(reading.spot == "02")
    }

    @Test("98BBE445 — B118 read as B1 and 18 is a floor and a bay")
    func floorAndBayOnOneRow() {
        let reading = PillarFloorSuggestion.reading(from: [
            PillarLine("B1 18", height: 0.269),
            PillarLine("B1 191", height: 0.121)
        ])

        #expect(reading.floorText == "B1")
        #expect(reading.spot == "18")
    }

    @Test("63205636 — a wall that says B3 and nothing else")
    func plainFloor() {
        let reading = PillarFloorSuggestion.reading(from: [PillarLine("B3", height: 0.114)])

        #expect(reading == PillarReading(floorText: "B3"))
    }

    @Test("9B89C54F — a pillar numbered B35 states no floor, and B35 is not one")
    func pillarNumberWithoutAFloor() {
        let reading = PillarFloorSuggestion.reading(from: [
            PillarLine("B35", height: 0.039),
            PillarLine("PARKING", height: 0.031),
            PillarLine("보형자", height: 0.035)
        ])

        #expect(reading.floorText == nil)
        #expect(reading.zone == "B35")
    }

    @Test("94695E1F — B4F is B4, and the bay is the pillar's own number")
    func redundantFloorSpelling() {
        // The wall paints `428` over `B4F`. `B4F` says basement and floor at once, which is
        // redundant and real; read as free text it threw away a floor stated plainly.
        let reading = PillarFloorSuggestion.reading(from: [
            PillarLine("428", height: 0.034),
            PillarLine("B4F", height: 0.019),
            PillarLine("428", height: 0.012),
            PillarLine("그르타/", height: 0.041)
        ])

        #expect(reading.floorText == "B4F")
        #expect(FloorValue.parse("B4F")?.displayText == "B4")
        #expect(reading.spot == "428")
    }

    @Test("873CE149 — a photo with nothing readable in it is silence")
    func nothingReadable() {
        #expect(PillarFloorSuggestion.reading(from: []) == .none)
    }

    /// Known incomplete, and kept as the honest record of it.
    @Test("3B922445 — the wall says B1 over 27 and Vision only returned the 27")
    func floorTheRecogniserNeverSaw() {
        let reading = PillarFloorSuggestion.reading(from: [PillarLine("27", height: 0.239)])

        // Nothing here can recover a floor that was never recognised: inventing one from a
        // bay number is precisely the misread §6a forbids. The bay is offered and the user
        // types the floor, which is the failure mode the feature is designed around.
        #expect(reading.floorText == nil)
        #expect(reading.spot == "27")
    }

    /// Not a car park at all — a press photo that happened to be in the folder.
    @Test("D336556B — a photo of something else offers no floor")
    func notACarPark() {
        let reading = PillarFloorSuggestion.reading(from: [
            PillarLine("81", height: 0.020),
            PillarLine("09", height: 0.039),
            PillarLine("YONHAPNEWS", height: 0.051)
        ])

        // The same `81` that is a floor when it fills the frame is not one at a fiftieth of
        // it. A suggested bay on a photo the user chose is harmless; a suggested floor is not.
        #expect(reading.floorText == nil)
    }
}

/// The same twelve photos as `PillarPhotoFixtureTests`, read by **ML Kit on the phone**
/// rather than by Vision on a Mac.
///
/// Two recognisers see one wall differently, and the rules have to hold for both: ML Kit
/// recovered the `B1` Vision missed on 3B922445 and read a whole row of pillars as one
/// forty-character line on 41EECF59. These are the readings the Android device actually
/// produced, and they are asserted on iOS as well because a rule that only works for the
/// recogniser it was written against is not a rule.
@Suite("Real pillar photos, as the phone's other recogniser saw them")
struct PillarPhotoCrossReaderFixtureTests {
    @Test("3B922445 — the floor Vision missed is read here, and it is the floor")
    func floorRecoveredByTheOtherReader() {
        let reading = PillarFloorSuggestion.reading(from: [
            PillarLine("B1", height: 0.114),
            PillarLine("27", height: 0.277)
        ])

        #expect(reading.floorText == "B1")
        #expect(reading.spot == "27")
    }

    @Test("41EECF59 — a whole row of pillars returned as one line names none of them")
    func mergedRowOfPillars() {
        let reading = PillarFloorSuggestion.reading(from: [
            PillarLine("od C13", height: 0.023),
            PillarLine("B2", height: 0.020),
            PillarLine("P", height: 0.025),
            PillarLine("B17 BL B16 BB15 Bh4 3 2 i", height: 0.123),
            PillarLine("수구", height: 0.021)
        ])

        #expect(reading.floorText == "B2")
        // Five labels on one row are five pillars the same distance away, and the stray
        // `3` and `2` beside them are the same tie. §6a leaves both blank.
        #expect(reading.zone == nil)
        #expect(reading.spot == nil)
    }

    @Test("98BBE445 — the next pillar down the row is not this pillar's zone")
    func farPillarIsNotTheZone() {
        let reading = PillarFloorSuggestion.reading(from: [
            PillarLine("기사지", height: 0.025),
            PillarLine("그ali", height: 0.073),
            PillarLine("B119", height: 0.094),
            PillarLine("B1 18", height: 0.213)
        ])

        #expect(reading.floorText == "B1")
        #expect(reading.spot == "18")
        // The car is at B118. `B119` is the next pillar, painted at less than half the
        // size of the row the bay came from.
        #expect(reading.zone == nil)
    }

    @Test("94695E1F — a P on a distant wall sign is not the zone")
    func smallLetterIsNotAZone() {
        let reading = PillarFloorSuggestion.reading(from: [
            PillarLine("428", height: 0.031),
            PillarLine("B4F", height: 0.016),
            PillarLine("428n", height: 0.014),
            PillarLine("P", height: 0.013)
        ])

        #expect(reading.floorText == "B4F")
        #expect(reading.spot == "428")
        #expect(reading.zone == nil)
    }

    @Test("873CE149 — the photo Vision read nothing in gives up its bay here")
    func bayRecoveredByTheOtherReader() {
        // The wall says B2 and 79; only the 79 was recognised, and a bay alone is still
        // worth offering.
        let reading = PillarFloorSuggestion.reading(from: [PillarLine("79", height: 0.106)])

        #expect(reading.floorText == nil)
        #expect(reading.spot == "79")
    }

    @Test("7B4E5633 — read cleanly, the B1 wall needs no correction at all")
    func badgeReadWithoutCorrection() {
        let reading = PillarFloorSuggestion.reading(from: [PillarLine("B1", height: 0.148)])

        #expect(reading == PillarReading(floorText: "B1"))
    }
}
