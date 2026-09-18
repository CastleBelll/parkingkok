import Foundation

/// What a feature depends on to report an event. An `AnalyticsEvent` is the only argument
/// it takes, which is the whole of the type-level guarantee.
protocol AnalyticsRecording: Sendable {
    func record(_ event: AnalyticsEvent)
}

/// The consent gate (docs/07 "동의").
///
/// Consent is re-read on every event rather than cached, so switching the toggle off stops
/// transmission on the next event with no invalidation step to get wrong.
///
/// **Nothing is buffered.** A dropped event is gone. docs/07 is explicit: if the user later
/// consents there must be no backlog to flush, and if they refuse there must never have
/// been one — consenting to future reporting is not consenting to the past.
struct AnalyticsRecorder: AnalyticsRecording {
    private let consent: any AnalyticsConsentStoring
    private let sink: any AnalyticsSending
    private let clock: any DateProviding

    init(consent: any AnalyticsConsentStoring, sink: any AnalyticsSending, clock: any DateProviding) {
        self.consent = consent
        self.sink = sink
        self.clock = clock
    }

    func record(_ event: AnalyticsEvent) {
        guard consent.isGranted else { return }
        sink.send(AnalyticsPayload(event, occurredAt: clock.now))
    }
}

/// A recorder that reports nothing, for the call sites that have no analytics stack —
/// tests, previews, and any composition that has not been given one.
struct DisabledAnalyticsRecorder: AnalyticsRecording {
    func record(_: AnalyticsEvent) {}
}

/// Builds the analytics stack for the running app.
///
/// One instance per process, mirroring `DetectionRuntime.shared`, so the settings toggle
/// and the recorder cannot disagree about consent.
///
/// **What changes when a Firebase project exists:** add a `FirebaseAnalyticsSink` that maps
/// `AnalyticsPayload` onto `Analytics.logEvent(_:parameters:)`, and return it from
/// `liveSink` below. That is the entire change — `AnalyticsEvent`, the consent gate and
/// every call site stay as they are.
enum AnalyticsComposition {
    static let consent: any AnalyticsConsentStoring = UserDefaultsAnalyticsConsentStore()

    static let recorder: any AnalyticsRecording = AnalyticsRecorder(
        consent: consent,
        sink: liveSink,
        clock: SystemDateProvider()
    )

    private static var liveSink: any AnalyticsSending {
        #if PK_DEV
            OSLogAnalyticsSink()
        #else
            NoOpAnalyticsSink()
        #endif
    }
}
