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
/// ### Session boundary — the event stream, not the driving session
/// `TraceSessionBoundaryPolicy` decides where one trip ends, from the gaps between events
/// alone. **Nothing here waits for vehicle evidence**: a walk, a stationary transition or a
/// lone significant-change sample opens a session just as a drive does. The bounded driving
/// session is still where the 1 Hz fixes come from, but it no longer decides what a session
/// *is* — capture opens only on vehicle evidence, so using it as the boundary dropped walking
/// and the subway entirely, which is the mistake §9 now names.
///
/// A fuel stop still splits into two traces (docs/05 §17 fixture 3) once the stop outlasts
/// the idle gap. Merging them would need a re-entry window, which is detection policy this
/// recorder has no business owning.
///
/// ### Process death
/// The open session is re-read from the store on first use and appended to, rather than
/// abandoned in favour of a fresh one. §9 defines a session as a run of events with no long
/// silence in it — a property of the stream, not of the process — and iOS is relaunched for
/// every significant change, so "one session per process" would cut a walk to the station
/// into a handful of two-event files and leave the two platforms disagreeing about the same
/// input. The open-session pointer lives beside the rolling-cap counter, and the file itself
/// is always complete: `endedAt` is rewritten on every append, so an abandoned trace needs
/// no repair pass even when nothing ever reopens it.
///
/// ### Cost
/// Append-only, driven entirely by signals that already arrived; nothing here polls or
/// starts a sensor, and the boundary is evaluated on arrival rather than on a timer.
/// Location fixes stream at roughly 1 Hz, so they are downsampled to
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
    /// a 30-minute drive would otherwise be ~1800 events, which the per-session boundary
    /// would rotate through mid-trip and which no fixture needs.
    static let minimumLocationInterval: TimeInterval = 15

    private let store: any TraceStoring
    private let metadata: TraceDeviceMetadata
    private var session: OpenSession?
    /// Newest Core Motion sample already folded in. Recorder-scoped rather than
    /// session-scoped: history is re-queried on every wake and the same samples come back,
    /// so the watermark has to outlive the session they were recorded into or a rotation
    /// would replay them into the next one.
    private var motionWatermark: Date?
    private var hasRestoredOpenSession = false
    /// What the store's open-session pointer says, so it is only rewritten when it moves.
    private var openSessionIdOnDisk: UUID?
    /// Last write failure, surfaced in diagnostics rather than thrown: recording must
    /// never break a detection callback.
    private(set) var lastFailure: String?

    init(store: any TraceStoring, metadata: TraceDeviceMetadata = .current) {
        self.store = store
        self.metadata = metadata
    }

    var isRecording: Bool {
        session != nil
    }

    var openSessionId: UUID? {
        session?.id
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
        guard !motionSamples.isEmpty else { return }
        restoreOpenSessionIfNeeded()

        for sample in motionSamples.sorted(by: { $0.timestamp < $1.timestamp }) {
            guard sample.timestamp > (motionWatermark ?? .distantPast) else { continue }
            motionWatermark = sample.timestamp
            guard sample.hasKnownActivity else { continue }
            record(motionSample: sample)
        }
    }

    /// One fix from the bounded session. The coordinate is measured against the previous
    /// fix and then discarded; only the metres are kept.
    mutating func record(fix: LocationFix) {
        restoreOpenSessionIfNeeded()
        var open = sessionAccepting(eventAt: fix.timestamp)
        open.accumulateDistance(to: fix)
        let appended = open.appendLocation(
            at: fix.timestamp,
            accuracy: fix.horizontalAccuracy,
            speed: fix.speed,
            carriesDistance: true
        )
        session = open
        if appended {
            persist()
        }
    }

    /// A significant-change sample. It carries no coordinate at all
    /// (`LocationQualitySample` drops it at the adapter), so it records quality without a
    /// distance — and it is never downsampled, because in a tunnel or a car park it may be
    /// the only location evidence there is.
    ///
    /// This is also the one location signal that arrives with no vehicle evidence behind
    /// it, which is why it has to be able to open a session of its own.
    mutating func record(qualitySample: LocationQualitySample) {
        restoreOpenSessionIfNeeded()
        var open = sessionAccepting(eventAt: qualitySample.timestamp)
        let appended = open.appendLocation(
            at: qualitySample.timestamp,
            accuracy: qualitySample.horizontalAccuracy,
            speed: nil,
            carriesDistance: false,
            bypassingInterval: true
        )
        session = open
        if appended {
            persist()
        }
    }

    /// Smart Detection was switched off: §9 closes the open session immediately.
    ///
    /// The file is already complete on disk, so closing is purely forgetting which session
    /// was open. The motion watermark deliberately stays put — opting back in must not
    /// replay the history that is already recorded in the closed session.
    mutating func closeOpenSession() {
        session = nil
        // Nothing left on disk may be reopened either, so the lazy restore is spent.
        hasRestoredOpenSession = true
        openSessionIdOnDisk = nil
        store.setOpenSessionId(nil)
    }

    // MARK: - Session boundary

    /// The open session when the boundary lets `date`'s event join it, `nil` when a new
    /// one has to be started. Kept non-mutating so a caller can ask without committing —
    /// a motion sample that turns out to carry no transition must not leave an empty trace.
    private func continuingSession(at date: Date) -> OpenSession? {
        guard rotationReason(at: date) == nil else { return nil }
        return session
    }

    /// The session `date`'s event belongs to: the open one while the boundary allows it,
    /// a fresh one otherwise. Never returns a session the event would be out of place in.
    private mutating func sessionAccepting(eventAt date: Date) -> OpenSession {
        if let open = continuingSession(at: date) {
            return open
        }
        if let rotation = rotationReason(at: date) {
            AppLog.detection.notice("trace session rotated: \(rotation.rawValue, privacy: .public)")
        }
        let opened = OpenSession(id: UUID(), startedAt: date)
        // Make room before recording, never during: eviction mid-trip would compete with
        // the fix stream for IO on the path that must stay cheap.
        store.prune(protecting: opened.id)
        return opened
    }

    private func rotationReason(at date: Date) -> TraceSessionBoundaryPolicy.RotationReason? {
        guard let session else { return nil }
        return TraceSessionBoundaryPolicy.rotationReason(
            startedAt: session.startedAt,
            endedAt: session.latestEventDate,
            eventCount: session.events.count,
            nextEventAt: date
        )
    }

    /// Picks the open session back up after process death.
    ///
    /// Everything the in-flight session needs is derivable from the events already written,
    /// except the previous coordinate — which is never stored, so the first fix after a
    /// restore simply carries no distance, exactly as the first fix of a new session does.
    private mutating func restoreOpenSessionIfNeeded() {
        guard !hasRestoredOpenSession else { return }
        hasRestoredOpenSession = true
        guard let id = store.openSessionId, let stored = store.load(id: id) else { return }
        session = OpenSession(restoring: stored)
        openSessionIdOnDisk = id
        motionWatermark = stored.events.last { $0.type.isMotion }?.date
    }

    // MARK: - Motion

    private mutating func record(motionSample sample: MotionSample) {
        let continuing = continuingSession(at: sample.timestamp)
        // A rotated session starts with no opinion, so its first sample restates the
        // activity rather than inheriting an edge from the trip before it.
        let types = (continuing?.flags ?? MotionFlags()).transitions(to: sample)

        guard !types.isEmpty else {
            // An activity §2 has no vocabulary for — `running`, say. It still ends a walk,
            // so the edges move even though nothing is recorded; opening a session for it
            // would leave an empty trace behind.
            if continuing != nil {
                session?.flags = MotionFlags(sample)
            }
            return
        }

        var open = continuing ?? sessionAccepting(eventAt: sample.timestamp)
        for type in types {
            open.append(.motion(type, at: sample.timestamp, confidence: sample.confidence))
        }
        open.flags = MotionFlags(sample)
        session = open
        persist()
    }

    // MARK: - Persistence

    /// Rewrites the whole file with `endedAt` brought up to date.
    ///
    /// A full rewrite rather than an append, because §9 fixes the on-disk shape as one
    /// JSON object. The payoff is that a file left behind by process death is already
    /// complete and needs no repair pass, and at the downsampled event rate this is a
    /// handful of small writes per minute.
    ///
    /// The session file is written before the open-session pointer moves. A process death
    /// between the two leaves a complete trace on disk and a pointer at the previous
    /// session, so the next event rotates instead of appending — evidence is kept and the
    /// boundary is at worst early, which is the right way round for a recording.
    private mutating func persist() {
        guard let open = session else { return }
        do {
            try store.write(open.snapshot(metadata: metadata))
            lastFailure = nil
            if openSessionIdOnDisk != open.id {
                store.setOpenSessionId(open.id)
                openSessionIdOnDisk = open.id
            }
        } catch {
            lastFailure = String(describing: error)
            AppLog.detection.error("trace write failed: \(String(describing: error), privacy: .public)")
        }
    }

    /// Core Motion's flags as the recorder last saw them.
    ///
    /// docs/05 §2 maps iOS onto enter/exit pairs it has no callbacks for — the semantics are
    /// *inferred* from history — so every edge here is the difference between two samples.
    private struct MotionFlags: Equatable {
        var automotive = false
        var walking = false
        var stationary = false

        init() {}

        init(_ sample: MotionSample) {
            automotive = sample.automotive
            walking = sample.walking
            stationary = sample.stationary
        }

        /// Ordered so the parking transition reads the way a fixture writes it: the
        /// vehicle ends, then the walk begins.
        func transitions(to sample: MotionSample) -> [TraceEventType] {
            var types: [TraceEventType] = []
            if automotive, !sample.automotive {
                types.append(.vehicleExit)
            }
            if !automotive, sample.automotive {
                types.append(.vehicleEnter)
            }
            if !walking, sample.walking {
                types.append(.walkingEnter)
            }
            if stationary, !sample.stationary {
                types.append(.stationaryExit)
            }
            if !stationary, sample.stationary {
                types.append(.stationaryEnter)
            }
            return types
        }
    }

    /// The in-flight trace. Only `previousFix` dies with the process; everything else is
    /// recoverable from the events already on disk.
    private struct OpenSession {
        let id: UUID
        let startedAt: Date
        var events: [TraceEvent] = []
        var flags = MotionFlags()

        /// **Memory only, never encoded.** Held for exactly one step so the next fix can
        /// be reduced to metres.
        var previousFix: LocationFix?
        /// Metres since the last recorded `location` event, summed across the fixes that
        /// downsampling dropped, so travel is not lost to the sampling interval.
        var pendingDistance: Double?
        var lastLocationEventAt: Date?
        var lastBucket: LocationAccuracyBucket?

        init(id: UUID, startedAt: Date) {
            self.id = id
            self.startedAt = startedAt
        }

        /// Reopens a session sealed on disk by a process that died mid-trip.
        ///
        /// The location state comes straight off the newest `location` event. The motion
        /// edges are read back from the events themselves, because a restored session that
        /// forgot them would emit a second `vehicle_enter` on the next sample of a drive
        /// that never stopped — fabricating a transition is worse than missing one.
        init(restoring stored: TraceSession) {
            id = stored.sessionId
            startedAt = stored.startDate
            events = stored.events

            let lastLocation = stored.events.last { $0.type == .location }
            lastLocationEventAt = lastLocation?.date
            lastBucket = lastLocation?.accuracy.flatMap { LocationAccuracyBucket(horizontalAccuracy: $0) }

            let motion = stored.events.filter(\.type.isMotion)
            flags.automotive = motion.last { $0.type == .vehicleEnter || $0.type == .vehicleExit }?
                .type == .vehicleEnter
            flags.stationary = motion.last { $0.type == .stationaryEnter || $0.type == .stationaryExit }?
                .type == .stationaryEnter
            // §2 has no `walking_exit`, so the walk is read as "still the newest thing Core
            // Motion said". A motion event stamped strictly later means it ended; one
            // stamped at the same instant came out of the same sample.
            if let walkingAt = motion.last(where: { $0.type == .walkingEnter })?.atMillis {
                flags.walking = !motion.contains { $0.atMillis > walkingAt }
            }
        }

        var latestEventDate: Date {
            events.last?.date ?? startedAt
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
        ///
        /// @return whether anything was appended. A fresh session always appends, because
        /// it has no previous event to be downsampled against.
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

            append(.location(
                at: date,
                accuracy: accuracy,
                speed: speed,
                distanceFromPreviousM: carriesDistance ? pendingDistance : nil
            ))
            pendingDistance = nil
            lastLocationEventAt = date
            if let degradation {
                append(.qualityDegraded(at: date, from: degradation.from, to: degradation.to))
            }
            return true
        }

        /// The ceiling is `TraceSessionBoundaryPolicy.maximumEvents`, applied by rotating
        /// into a new session before the event arrives — never by dropping it here. A
        /// truncated trace looks exactly like a trip that ended, which is the one thing a
        /// recording must not lie about.
        mutating func append(_ event: TraceEvent) {
            events.append(event)
        }

        func snapshot(metadata: TraceDeviceMetadata) -> TraceSession {
            TraceSession(
                sessionId: id,
                metadata: metadata,
                startedAt: startedAt,
                endedAt: latestEventDate,
                events: events
            )
        }
    }
}
