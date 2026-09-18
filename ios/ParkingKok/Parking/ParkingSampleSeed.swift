#if PK_DEV
    import Foundation

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

        /// Opens the app on one screen rather than home, so every product surface can be
        /// photographed without a way to drive the touchscreen.
        ///
        /// ```sh
        ///   --environment-variables '{"PK_SEED_SAMPLE_PARKING":"1","PK_INITIAL_ROUTE":"history"}'
        /// ```
        static func initialRoute(activeParkingID: UUID?) -> AppRoute? {
            switch ProcessInfo.processInfo.environment["PK_INITIAL_ROUTE"] {
            case "history": .history
            case "settings": .settings
            case "diagnostics": .diagnostics
            case "detail": activeParkingID.map(AppRoute.parkingDetail(id:))
            default: nil
            }
        }

        /// Mirrors `design-references/01-home-main.png` so the two can be held side by
        /// side: B3 · A구역 142, parked 1시간 24분 ago, over three earlier records.
        static func apply(to store: any ParkingStoring, now: Date) throws {
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

            try store.startSession(
                sample(
                    startedAt: now.addingTimeInterval(-84 * 60),
                    floor: "B3",
                    zone: "A구역",
                    spot: "142",
                    source: .detected
                )
            )
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
