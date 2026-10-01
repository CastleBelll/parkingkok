import XCTest

/// Taps what a person taps, on the seeded DEV build, and checks that something happens.
///
/// Written after the 2026-10-01 device review ("눌리지도 않는 버튼들"): reading the code
/// had said every one of these worked. Run with the `ParkingPinUITests` scheme on a
/// simulator; it is not part of CI's unit run.
final class ControlSmokeTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        // The seeded parking (B3 · A구역 142, three earlier records, one pending candidate),
        // and the first-run question already answered so no alert covers the screen.
        app.launchEnvironment["PK_SEED_SAMPLE_PARKING"] = "1"
        app.launchArguments += ["-pk.smartDetection.firstRunAnswered", "YES"]
    }

    private func launch(route: String? = nil) {
        if let route { app.launchEnvironment["PK_INITIAL_ROUTE"] = route }
        app.launch()
    }

    private func element(_ label: String) -> XCUIElement {
        app.descendants(matching: .any)[label].firstMatch
    }

    private func tapWhenReady(_ label: String, file: StaticString = #filePath, line: UInt = #line) {
        let target = element(label)
        XCTAssertTrue(target.waitForExistence(timeout: 10), "\(label) never appeared", file: file, line: line)
        target.tap()
    }

    // MARK: - Home

    func testPlusKeyMovesTheFloorUpOne() {
        launch()
        // The hero reads as one VoiceOver element, "현재 주차 위치, 지하 3층".
        XCTAssertTrue(element("현재 주차 위치, 지하 3층").waitForExistence(timeout: 10))

        tapWhenReady("한 층 위로")

        XCTAssertTrue(element("현재 주차 위치, 지하 2층").waitForExistence(timeout: 5), "B3 + 1 should read B2")
    }

    func testLocationRowOpensTheDetail() {
        launch()

        tapWhenReady("주차 위치 보기")

        XCTAssertTrue(app.navigationBars["주차 위치"].waitForExistence(timeout: 5))
    }

    func testSeeAllOpensHistoryAndARowOpensItsDetail() {
        launch()

        tapWhenReady("전체보기")
        XCTAssertTrue(app.navigationBars["주차 기록"].waitForExistence(timeout: 5))
        tapWhenReady("B2 · C구역 38")

        XCTAssertTrue(app.navigationBars["주차 위치"].waitForExistence(timeout: 5))
    }

    // MARK: - Detail

    func testDirectionsLeavesForAMapApp() {
        launch(route: "detail")

        tapWhenReady("길찾기")

        // One map app (the simulator has only Apple Maps): it opens. More: a chooser.
        let maps = XCUIApplication(bundleIdentifier: "com.apple.Maps")
        let chooser = app.staticTexts["길찾기 앱 선택"]
        let reached = maps.wait(for: .runningForeground, timeout: 10) || chooser.exists
        XCTAssertTrue(reached, "길찾기 opened neither a map app nor the chooser")
    }

    func testEditOpensTheFormFilledWithTheRecord() {
        launch(route: "detail")

        tapWhenReady("수정")

        let floor = app.textFields["예: B3, 지하 3층, 3F"]
        XCTAssertTrue(floor.waitForExistence(timeout: 5))
        XCTAssertEqual(floor.value as? String, "B3")
        tapWhenReady("취소")
    }

    func testShareOffersTheSystemSheet() {
        launch(route: "detail")

        tapWhenReady("위치 보내기")

        // The share sheet is another process's UI; its close control is the stable handle.
        let sheet = app.otherElements["ActivityListView"]
        let close = app.buttons["Close"]
        let appeared = sheet.waitForExistence(timeout: 8) || close.exists || app.buttons["닫기"].exists
        XCTAssertTrue(appeared, "위치 보내기 opened no share sheet")
    }

    func testThePhotoOpensLarge() {
        // The seeded parking carries a photo; the card is its one control (2026-10-01).
        launch(route: "detail")
        app.swipeUp()

        tapWhenReady("주차 사진 크게 보기")

        XCTAssertTrue(element("닫기").waitForExistence(timeout: 5), "the photo viewer did not open")
    }

    // MARK: - Confirmation

    func testNotParkingAnswersAndLeaves() {
        launch(route: "confirm")
        XCTAssertTrue(app.staticTexts["주차한 것 같아요"].waitForExistence(timeout: 10))

        tapWhenReady("주차 아님")

        XCTAssertFalse(app.buttons["주차 아님"].waitForExistence(timeout: 2), "the screen should have closed")
    }

    func testManualEntryOpensTheForm() {
        launch(route: "confirm")

        tapWhenReady("직접 입력")

        XCTAssertTrue(app.textFields["예: B3, 지하 3층, 3F"].waitForExistence(timeout: 5))
    }

    func testLastTimesFloorFillsTheFormOnTheTap() {
        // docs/02 §18: the seed's newest past parking (B2 · C구역) is at the candidate's spot.
        launch(route: "confirm")
        tapWhenReady("직접 입력")
        let floor = app.textFields["예: B3, 지하 3층, 3F"]
        XCTAssertTrue(floor.waitForExistence(timeout: 5))
        XCTAssertTrue(element("지난번 이 주차장").waitForExistence(timeout: 5), "no offer")

        tapWhenReady("채우기")

        XCTAssertEqual(floor.value as? String, "B2")
        XCTAssertEqual(app.textFields["예: A구역"].value as? String, "C구역")
        XCTAssertFalse(element("지난번 이 주차장").exists, "the offer should be spent")
    }

    // MARK: - Settings

    func testSettingsHasNoPlaceholderRows() {
        launch(route: "settings")
        XCTAssertTrue(app.navigationBars["설정"].waitForExistence(timeout: 10))

        XCTAssertFalse(app.staticTexts["준비 중"].exists)
        XCTAssertFalse(app.staticTexts["Plus 구독"].exists)
    }
}
