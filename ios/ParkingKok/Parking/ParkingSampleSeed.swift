#if PK_DEV
    import Foundation
    import UIKit

    /// DEV-only fixture so the product screens can be photographed on a real device.
    ///
    /// The UI states worth reviewing — an active parking with a floor, a history with
    /// both sources in it — take a drive and a car park to reach otherwise, and there is
    /// no way to drive the touchscreen from the command line. This is the same shape of
    /// hook as `PK_FORCE_DRIVING_SESSION` in `DetectionRuntime`, and like that one it is
    /// compiled out of STAGING and PROD entirely.
    ///
    /// ```sh
    /// xcrun devicectl device process launch --device <udid> \
    ///   --environment-variables '{"PK_SEED_SAMPLE_PARKING":"1"}' com.parkingkok.app.dev
    /// ```
    ///
    /// **Replaces** whatever is stored, so the screenshot is the same every run.
    @MainActor
    enum ParkingSampleSeed {
        static var isRequested: Bool {
            ProcessInfo.processInfo.environment["PK_SEED_SAMPLE_PARKING"] == "1"
        }

        /// Seeds the active parking with neither a coordinate nor a photo.
        ///
        /// That is the FR-001 state — saved with location permission denied — and it is
        /// the one the detail screen has to degrade into: no map card, `길찾기` disabled
        /// and explained, an empty photo panel. Unreachable from the seeded fixture
        /// otherwise, and there is no way to drive the touchscreen to a history record.
        ///
        /// ```sh
        ///   --environment-variables '{"PK_SEED_SAMPLE_PARKING":"1","PK_SEED_WITHOUT_LOCATION":"1"}'
        /// ```
        static var isWithoutLocationRequested: Bool {
            ProcessInfo.processInfo.environment["PK_SEED_WITHOUT_LOCATION"] == "1"
        }

        /// Opens the app on one screen rather than home, so every product surface can be
        /// photographed without a way to drive the touchscreen.
        ///
        /// ```sh
        ///   --environment-variables '{"PK_SEED_SAMPLE_PARKING":"1","PK_INITIAL_ROUTE":"history"}'
        /// ```
        static func initialRoute(activeParkingID: UUID?) -> AppRoute? {
            switch ProcessInfo.processInfo.environment["PK_INITIAL_ROUTE"] {
            case "history": .history
            case "settings": .settings(focus: nil)
            case "diagnostics": .diagnostics
            case "detail": activeParkingID.map(AppRoute.parkingDetail(id:))
            default: nil
            }
        }

        /// Where the seeded active parking is pinned for the FR-008 map screenshot.
        ///
        /// Seoul City Hall. The original fixture carried no coordinate at all, on the
        /// grounds that a screenshot must never show a place anyone lives — which is
        /// still the rule. A civic landmark is not that place, and `03-parking-detail.png`
        /// cannot be compared against a screen with the map card missing.
        static let sampleLatitude = 37.566_295
        static let sampleLongitude = 126.977_945
        /// Deliberately underground-grade. A 4m fix would make the accuracy circle
        /// invisible and would not exercise the honest-framing rule at all.
        static let sampleAccuracyMeters: Double = 24

        /// Mirrors `design-references/01-home-main.png` so the two can be held side by
        /// side: B3 · A구역 142, parked 1시간 24분 ago, over three earlier records.
        ///
        /// `photoStore` is optional so the fixture still applies when photo storage is
        /// unavailable — the same degradation the product has.
        static func apply(
            to store: any ParkingStoring,
            photoStore: (any ParkingPhotoStoring)? = nil,
            now: Date
        ) async throws {
            try store.deleteAll()

            for offset in past {
                var record = sample(
                    startedAt: now.addingTimeInterval(-offset.startedHoursAgo * 3600),
                    floor: offset.floor,
                    zone: offset.zone,
                    spot: offset.spot,
                    source: offset.source
                )
                record.endedAt = record.startedAt.addingTimeInterval(offset.durationHours * 3600)
                try store.startSession(record)
                try store.endSession(id: record.id, at: record.endedAt ?? now)
            }

            var active = sample(
                startedAt: now.addingTimeInterval(-84 * 60),
                floor: "B3",
                zone: "A구역",
                spot: "142",
                source: .detected
            )
            if !isWithoutLocationRequested {
                active.location = ParkedLocation(
                    latitude: sampleLatitude,
                    longitude: sampleLongitude,
                    horizontalAccuracy: sampleAccuracyMeters,
                    capturedAt: now.addingTimeInterval(-84 * 60)
                )
                if let photoStore, let image = placeholderPhotoData() {
                    active.photoRelativePath = try? await photoStore.save(image, for: active.id)
                }
            }
            try store.startSession(active)
        }

        /// A drawn stand-in for a photo of a car park pillar.
        ///
        /// Generated rather than bundled: a real photograph in the repository would be
        /// somebody's car park, and the screenshot only needs the panel to be occupied at
        /// a realistic aspect ratio.
        private static func placeholderPhotoData() -> Data? {
            let size = CGSize(width: 1600, height: 1200)
            let renderer = UIGraphicsImageRenderer(size: size)
            let image = renderer.image { context in
                UIColor(red: 0.42, green: 0.45, blue: 0.5, alpha: 1).setFill()
                context.fill(CGRect(origin: .zero, size: size))
                UIColor(red: 0.16, green: 0.42, blue: 0.82, alpha: 1).setFill()
                context.fill(CGRect(x: 560, y: 0, width: 480, height: size.height))
                let label = NSAttributedString(
                    string: "B3\nA구역",
                    attributes: [
                        .font: UIFont.systemFont(ofSize: 220, weight: .heavy),
                        .foregroundColor: UIColor.white
                    ]
                )
                label.draw(in: CGRect(x: 600, y: 300, width: 420, height: 700))
            }
            return image.jpegData(compressionQuality: 0.9)
        }

        private struct PastParking {
            let startedHoursAgo: Double
            let durationHours: Double
            let floor: String
            let zone: String?
            let spot: String?
            let source: ParkingSource
        }

        private static let past: [PastParking] = [
            PastParking(
                startedHoursAgo: 26,
                durationHours: 2,
                floor: "B2",
                zone: "C구역",
                spot: "38",
                source: .manual
            ),
            PastParking(
                startedHoursAgo: 120,
                durationHours: 3,
                floor: "B4",
                zone: "A구역",
                spot: "112",
                source: .detected
            ),
            PastParking(
                startedHoursAgo: 168,
                durationHours: 1.5,
                floor: "B1",
                zone: "출구 3 근처",
                spot: nil,
                source: .manual
            ),
            PastParking(
                startedHoursAgo: 240,
                durationHours: 4,
                floor: "B5",
                zone: nil,
                spot: "201",
                source: .detected
            )
        ]

        private static func sample(
            startedAt: Date,
            floor: String,
            zone: String?,
            spot: String?,
            source: ParkingSource
        ) -> ParkingSession {
            ParkingSession(
                id: UUID(),
                startedAt: startedAt,
                endedAt: nil,
                source: source,
                confidenceBucket: source == .detected ? .high : nil,
                // No coordinate: the fixture has no business carrying one, and a screenshot
                // of it must never show a place anyone lives.
                location: nil,
                floor: FloorValue.parse(floor),
                zone: zone,
                spot: spot,
                memo: nil,
                photoRelativePath: nil,
                createdAt: startedAt,
                updatedAt: startedAt
            )
        }
    }
#endif
