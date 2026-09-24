import Testing
@testable import ParkingPin

/// FR-005 and docs/02_PRODUCT_SCOPE_AND_FLOWS.md §6.
struct FloorValueTests {
    @Test(
        "Basement spellings all parse to the same floor",
        arguments: ["B3", "b3", "B 3", "지하3", "지하 3", "지하3층", "지하 3층"]
    )
    func parsesBasement(_ input: String) {
        // Arrange / Act
        let floor = FloorValue.parse(input)

        // Assert
        #expect(floor?.kind == .basement)
        #expect(floor?.number == 3)
        #expect(floor?.displayText == "B3")
        #expect(floor?.level == -3)
        // §6: "preserve raw text".
        #expect(floor?.raw == input)
    }

    @Test(
        "Above-ground spellings all parse to the same floor",
        arguments: ["3F", "3f", "3 F", "3층", "지상 3층", "지상3", "3"]
    )
    func parsesGround(_ input: String) {
        // Arrange / Act
        let floor = FloorValue.parse(input)

        // Assert
        #expect(floor?.kind == .ground)
        #expect(floor?.number == 3)
        #expect(floor?.displayText == "3F")
        #expect(floor?.level == 3)
    }

    @Test(
        "Anything that does not parse is kept verbatim as free text",
        arguments: ["P3", "옥상", "주차타워 2동", "B", "지하", "3층 왼쪽", "B-3"]
    )
    func fallsBackToFreeText(_ input: String) {
        // Arrange / Act
        let floor = FloorValue.parse(input)

        // Assert
        #expect(floor?.kind == .freeText)
        #expect(floor?.number == nil)
        #expect(floor?.displayText == input)
        #expect(floor?.isSteppable == false)
    }

    @Test("Surrounding whitespace is trimmed but inner text is not rewritten")
    func trimsInput() {
        // Arrange / Act
        let parsed = FloorValue.parse("  B2  ")
        let freeText = FloorValue.parse("  옥상 주차  ")

        // Assert
        #expect(parsed?.raw == "B2")
        #expect(parsed?.number == 2)
        #expect(freeText?.raw == "옥상 주차")
    }

    @Test("Blank input is no floor at all, not an empty one", arguments: ["", "   ", "\n"])
    func rejectsBlankInput(_ input: String) {
        #expect(FloorValue.parse(input) == nil)
    }

    @Test("Floor zero does not exist, so B0 and 0F stay free text", arguments: ["B0", "0F", "0층", "지하 0층"])
    func rejectsZero(_ input: String) {
        #expect(FloorValue.parse(input)?.kind == .freeText)
    }

    @Test("A number past the ceiling is kept as text rather than pretended to be understood")
    func rejectsOversizedNumber() {
        // Arrange / Act
        let floor = FloorValue.parse("B1000")

        // Assert — 4 digits never match the pattern, so this lands in free text.
        #expect(floor?.kind == .freeText)
        #expect(floor?.raw == "B1000")
    }

    // ── Stepping ────────────────────────────────────────────────────────────

    @Test("Stepping up inside the basement moves towards ground level")
    func stepsUpWithinBasement() {
        // Arrange
        let floor = FloorValue.parse("B3")

        // Act
        let stepped = floor?.stepped(by: 1)

        // Assert
        #expect(stepped?.displayText == "B2")
        #expect(stepped?.kind == .basement)
    }

    @Test("Stepping up from B1 lands on 1F — there is no floor zero")
    func stepsAcrossGroundGoingUp() {
        // Arrange
        let floor = FloorValue.parse("B1")

        // Act
        let stepped = floor?.stepped(by: 1)

        // Assert
        #expect(stepped?.kind == .ground)
        #expect(stepped?.number == 1)
        #expect(stepped?.displayText == "1F")
    }

    @Test("Stepping down from 1F lands on B1")
    func stepsAcrossGroundGoingDown() {
        // Arrange
        let floor = FloorValue.parse("1F")

        // Act
        let stepped = floor?.stepped(by: -1)

        // Assert
        #expect(stepped?.kind == .basement)
        #expect(stepped?.displayText == "B1")
    }

    @Test("A stepped floor carries its own normalised raw text, not the original words")
    func steppedFloorRewritesRawText() {
        // Arrange
        let floor = FloorValue.parse("지하 3층")

        // Act
        let stepped = floor?.stepped(by: 1)

        // Assert — "지하 3층" no longer describes B2.
        #expect(stepped?.raw == "B2")
    }

    @Test("Free text refuses to step, so the caller can disable the control")
    func freeTextDoesNotStep() {
        // Arrange
        let floor = FloorValue.parse("옥상")

        // Act / Assert
        #expect(floor?.stepped(by: 1) == nil)
        #expect(floor?.stepped(by: -1) == nil)
    }

    @Test("Stepping past the outermost floor refuses rather than wrapping")
    func refusesToStepPastBounds() {
        // Arrange
        let deepest = FloorValue.stored(raw: "B999", kind: .basement, number: FloorValue.maximumNumber)
        let highest = FloorValue.stored(raw: "999F", kind: .ground, number: FloorValue.maximumNumber)

        // Act / Assert
        #expect(deepest.stepped(by: -1) == nil)
        #expect(highest.stepped(by: 1) == nil)
        #expect(deepest.stepped(by: 1)?.displayText == "B998")
    }

    @Test("A zero step is a no-op, not a rewrite")
    func zeroStepDoesNothing() {
        #expect(FloorValue.parse("B3")?.stepped(by: 0) == nil)
    }

    @Test("Multi-floor steps jump the missing zero exactly once")
    func multiFloorStepSkipsZeroOnce() {
        // Arrange
        let floor = FloorValue.parse("B2")

        // Act — -2 + 3 = +1, and the zero that model skips is the one that does not exist.
        let stepped = floor?.stepped(by: 3)

        // Assert
        #expect(stepped?.displayText == "1F")
    }

    @Test("VoiceOver reads a floor the way docs/10 §12 spells it")
    func speaksFloorForVoiceOver() {
        #expect(FloorValue.parse("B3")?.accessibilityText == "지하 3층")
        #expect(FloorValue.parse("2F")?.accessibilityText == "지상 2층")
        #expect(FloorValue.parse("옥상")?.accessibilityText == "옥상")
    }

    @Test("A stored value is rebuilt without re-parsing, so an old record keeps its kind")
    func rebuildsStoredValue() {
        // Arrange / Act — text this build would parse as ground, stored as free text.
        let restored = FloorValue.stored(raw: "3", kind: .freeText, number: 7)

        // Assert — the stored kind wins, and free text never carries a number.
        #expect(restored.kind == .freeText)
        #expect(restored.number == nil)
        #expect(restored.displayText == "3")
    }
}
