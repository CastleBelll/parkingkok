import Foundation

/// Turns the signals the detection stack already receives into a docs/05 §9 trace.
///
/// ### Why this exists
/// Tuning the engine needs 100+ real parking sessions (docs/05 §18), and repeating twenty
/// real drives is the bottleneck in front of Gate M0. Recording ordinary movement turns
/// every commute into data, and it is the only way to get the negative cases §17 demands:
/// a bus, a subway or a taxi can be ridden without owning a car, and a passenger seat is
/// a valid session.
///
/// ### Session boundary — one bounded driving session is one trace
/// The alternative was the Smart Detection opt-in, which would make a single trace span
/// days and hundreds of idle events, defeating the rolling cap's granularity and
/// producing nothing a fixture could be cut from — a §8 fixture is one trip.
///
/// The bounded driving session is already the coordinator's only notion of a trip, so
/// reusing it adds no second lifecycle to keep correct and no extra battery cost. It also
/// covers the negative modes despite the "driving" name: Core Motion reports `automotive`
/// on a bus, a subway and a taxi alike, so those rides open a session and get recorded,
/// and the human label is what separates them afterwards.
///
/// Two consequences are deliberate, not oversights:
/// - A walk that never opened a session records nothing. That is the correct outcome —
///   the engine did nothing, so there is nothing to compare an expectation against. A walk
///   that *did* open a session is the interesting false positive, and it is recorded.
/// - A fuel stop splits into two traces (docs/05 §17 fixture 3). Merging them would need a
///   re-entry window, which is detection policy this recorder has no business owning.
///
/// ### Cost
/// Append-only, driven entirely by signals that already arrived; nothing here polls or
/// starts a sensor. Location fixes stream at roughly 1 Hz, so they are downsampled to
/// `minimumLocationInterval` — with the quality-transition exception, since a degradation
/// is the event a fixed interval would most likely miss.
///
/// ### Privacy
/// `previousFix` is the one coordinate-bearing value here. It lives in memory only, for
/// exactly as long as it takes to measure the next step, and only the metres reach
/// `TraceEvent`. `TraceSession` has no field a coordinate could be written to, and a test
/// on the encoded bytes enforces it. **Adding a coordinate is never the fix for a failing
/// test** (docs/05 §9, CLAUDE.md Hard Constraints).
struct TraceRecorder {
    /// Recorded `location` events per trip, at most one per this interval. 1 Hz fixes over
    /// a 30-minute drive would otherwise be ~1800 events, which the per-session cap would
    /// truncate mid-trip and which no fixture needs.
    static let minimumLocationInterval: TimeInterval = 15

    private let store: any TraceStoring
    private let metadata: TraceDeviceMetadata
    private let retention: TraceRetentionPolicy
    private var session: OpenSession?
    /// Last write failure, surfaced in diagnostics rather than thrown: recording must
    /// never break a detection callback.
    private(set) var lastFailure: String?

    init(
        store: any TraceStoring,
        metadata: TraceDeviceMetadata = .current,
        retention: TraceRetentionPolicy = .standard
    ) {
        self.store = store
        self.metadata = metadata
        self.retention = retention
    }

    var isRecording: Bool {
        session != nil
    }

    var openSessionId: UUID? {
        session?.id
    }

    // MARK: - Lifecycle

    /// Opens a trace. `date` anchors the session, and motion samples older than it are
    /// ignored so the replayed history of an earlier trip cannot bleed in.
    mutating func beginSession(at date: Date) {
        guard session == nil else { return }
        session = OpenSession(id: UUID(), startedAt: date, maximumEvents: retention.maximumEventsPerSession)
        // Make room before recording, never during: eviction mid-trip would compete with
        // the fix stream for IO on the path that must stay cheap.
        store.prune(protecting: session?.id)
        persist(endedAt: date)
    }

    mutating func endSession(at date: Date) {
        guard session != nil else { return }
        persist(endedAt: date)
        session = nil
    }

    // MARK: - Recording

    /// Folds replayed Core Motion history into §2's normalized events.
    ///
    /// History is re-queried on every wake, so the same sample is seen repeatedly. A
    /// timestamp watermark makes that idempotent, and the transitions are edge-triggered
    /// so a flag that stays true does not emit an event per slice.
    ///
    /// Samples with no known activity advance the watermark but never move an edge:
    /// Core Motion routinely returns opinion-free slices, and letting one reset the flags
    /// would fabricate a re-entry event on the next real sample.
    mutating func record(motionSamples: [MotionSample]) {
        guard var open = session, !motionSamples.isEmpty else { return }
        var appended = false

        for sample in motionSamples.sorted(by: { $0.timestamp < $1.timestamp }) {
            guard sample.timestamp >= open.startedAt,
                  sample.timestamp > (open.motionWatermark ?? .distantPast)
            else { continue }
            open.motionWatermark = sample.timestamp
            guard sample.hasKnownActivity else { continue }

            for type in open.transitions(for: sample) {
                appended = open.append(.motion(type, at: sample.timestamp, confidence: sample.confidence)) || appended
            }
            open.applyFlags(of: sample)
        }

        session = open
        if appended {
            persist(endedAt: open.latestEventDate)
        }
    }

    /// One fix from the bounded session. The coordinate is measured against the previous
    /// fix and then discarded; only the metres are kept.
    mutating func record(fix: LocationFix) {
        guard var open = session, fix.timestamp >= open.startedAt else { return }
        open.accumulateDistance(to: fix)
        let appended = open.appendLocation(
            at: fix.timestamp,
            accuracy: fix.horizontalAccuracy,
            speed: fix.speed,
            carriesDistance: true
        )
        session = open
        if appended {
            persist(endedAt: open.latestEventDate)
        }
    }

    /// A significant-change sample that arrived while a trace was open. It carries no
    /// coordinate at all (`LocationQualitySample` drops it at the adapter), so it records
    /// quality without a distance — and it is never downsampled, because in a tunnel or a
    /// car park it may be the only location evidence there is.
    mutating func record(qualitySample: LocationQualitySample) {
        guard var open = session, qualitySample.timestamp >= open.startedAt else { return }
        let appended = open.appendLocation(
            at: qualitySample.timestamp,
            accuracy: qualitySample.horizontalAccuracy,
            speed: nil,
            carriesDistance: false,
            bypassingInterval: true
        )
        session = open
        if appended {
            persist(endedAt: open.latestEventDate)
        }
    }

    // MARK: - Persistence

    /// Rewrites the whole file with `endedAt` brought up to date.
    ///
    /// A full rewrite rather than an append, because §9 fixes the on-disk shape as one
    /// JSON object. The payoff is that a file left behind by process death is already
    /// complete and needs no repair pass, and at the downsampled event rate this is a
    /// handful of small writes per minute.
    private mutating func persist(endedAt: Date) {
        guard let open = session else { return }
        do {
            try store.write(open.snapshot(metadata: metadata, endedAt: endedAt))
            lastFailure = nil
        } catch {
            lastFailure = String(describing: error)
            AppLog.detection.error("trace write failed: \(String(describing: error), privacy: .public)")
        }
    }

    /// The in-flight trace. Everything here is per-session and dies with the process; a
    /// sealed file on disk is the only thing that outlives it.
    private struct OpenSession {
        let id: UUID
        let startedAt: Date
        let maximumEvents: Int
        var events: [TraceEvent] = []

        /// **Memory only, never encoded.** Held for exactly one step so the next fix can
        /// be reduced to metres.
        var previousFix: LocationFix?
        /// Metres since the last recorded `location` event, summed across the fixes that
        /// downsampling dropped, so travel is not lost to the sampling interval.
        var pendingDistance: Double?
        var lastLocationEventAt: Date?
        var lastBucket: LocationAccuracyBucket?
        var motionWatermark: Date?
        var wasAutomotive = false
        var wasWalking = false
        var wasStationary = false

        var latestEventDate: Date {
            events.last?.date ?? startedAt
        }

        /// docs/05 §2 mapped onto Core Motion's independent flags. iOS has no vehicle-exit
        /// transition of its own — §2 says the enter/exit semantics are *inferred* from
        /// history — so an exit is an automotive flag that stopped being set. `stationary`
        /// gets the same enter/exit treatment: docs/04_ANDROID §2 uses STILL ENTER/EXIT as
        /// supporting evidence, and the flag carries both edges here too.
        ///
        /// Ordered so the parking transition reads the way a fixture writes it: the
        /// vehicle ends, then the walk begins.
        func transitions(for sample: MotionSample) -> [TraceEventType] {
            var types: [TraceEventType] = []
            if wasAutomotive, !sample.automotive {
                types.append(.vehicleExit)
            }
            if !wasAutomotive, sample.automotive {
                types.append(.vehicleEnter)
            }
            if !wasWalking, sample.walking {
                types.append(.walkingEnter)
            }
            if wasStationary, !sample.stationary {
                types.append(.stationaryExit)
            }
            if !wasStationary, sample.stationary {
                types.append(.stationaryEnter)
            }
            return types
        }

        mutating func applyFlags(of sample: MotionSample) {
            wasAutomotive = sample.automotive
            wasWalking = sample.walking
            wasStationary = sample.stationary
        }

        /// Measures the step and drops the coordinate. Mirrors `DrivingEvidence.record`:
        /// an implausible jump neither adds distance nor becomes the new anchor, because
        /// anchoring on a jump would make the next legitimate fix look like one too
        /// (docs/05 §5).
        mutating func accumulateDistance(to fix: LocationFix) {
            guard fix.isValid else { return }
            guard let previous = previousFix else {
                previousFix = fix
                return
            }
            guard LocationOutlierPolicy.isPlausibleStep(from: previous, to: fix) else { return }
            pendingDistance = (pendingDistance ?? 0) + GeoDistance.meters(from: previous, to: fix)
            previousFix = fix
        }

        /// Records a location observation, plus the quality transition it may imply.
        ///
        /// Quality is tracked on every observation, not only the recorded ones — a
        /// degradation that happened between two downsampled fixes is still real, and it
        /// forces the observation to be recorded so the trace says when quality fell.
        mutating func appendLocation(
            at date: Date,
            accuracy: Double,
            speed: Double?,
            carriesDistance: Bool,
            bypassingInterval: Bool = false
        ) -> Bool {
            let bucket = LocationAccuracyBucket(horizontalAccuracy: accuracy)
            let previousBucket = lastBucket
            if let bucket {
                lastBucket = bucket
            }

            var degradation: (from: LocationAccuracyBucket, to: LocationAccuracyBucket)?
            if let previousBucket, let bucket, bucket > previousBucket {
                degradation = (previousBucket, bucket)
            }

            let isDue = lastLocationEventAt
                .map { date.timeIntervalSince($0) >= TraceRecorder.minimumLocationInterval } ?? true
            guard isDue || bypassingInterval || degradation != nil else { return false }

            let distance = carriesDistance ? pendingDistance : nil
            var appended = append(.location(
                at: date,
                accuracy: accuracy,
                speed: speed,
                distanceFromPreviousM: distance
            ))
            if appended {
                pendingDistance = nil
                lastLocationEventAt = date
            }
            if let degradation {
                appended = append(.qualityDegraded(at: date, from: degradation.from, to: degradation.to)) || appended
            }
            return appended
        }

        /// Returns whether the event was kept. The per-session ceiling stops the trace
        /// growing without closing it: the session is still the engine's, and truncation
        /// shows up as a session sitting exactly at the cap.
        @discardableResult
        mutating func append(_ event: TraceEvent) -> Bool {
            guard events.count < maximumEvents else { return false }
            events.append(event)
            return true
        }

        func snapshot(metadata: TraceDeviceMetadata, endedAt: Date) -> TraceSession {
            TraceSession(
                sessionId: id,
                metadata: metadata,
                startedAt: startedAt,
                endedAt: max(endedAt, latestEventDate),
                events: events
            )
        }
    }
}
