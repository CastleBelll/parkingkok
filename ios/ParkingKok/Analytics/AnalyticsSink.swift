import Foundation

/// Where a payload goes once the consent gate has let it through.
///
/// **The replaceable seam.** docs/07 §2 fixed Firebase Analytics as the transport, but
/// there is no Firebase project yet and therefore no `GoogleService-Info.plist`, so this
/// build ships a sink that sends nothing. Adding Firebase is one new conformance plus one
/// line in `AnalyticsComposition`; nothing above this protocol knows what the transport is.
///
/// `Sendable`, and no `async`: detection will record from `BackgroundCoordinator`'s actor
/// isolation, and a call site that has to await its own telemetry would be tempted to
/// skip it.
protocol AnalyticsSending: Sendable {
    func send(_ payload: AnalyticsPayload)
}

/// The transport this milestone ships: none.
///
/// Not a stub to be tolerated — with no Firebase project, "drop it" is the only honest
/// behaviour. Buffering until one exists would be the buffering docs/07 "동의" forbids,
/// only worse, because nobody would have consented to the backlog.
struct NoOpAnalyticsSink: AnalyticsSending {
    func send(_: AnalyticsPayload) {}
}

#if PK_DEV
    /// DEV-only readout, so the wiring can be verified on a device before any transport
    /// exists. Off in STAGING and PROD by construction — the type is not compiled there.
    ///
    /// Safe to log: a payload's keys are `AnalyticsPayload.Key.all` and its values are
    /// buckets and booleans, so there is no coordinate in here to leak (docs/09 §11).
    struct OSLogAnalyticsSink: AnalyticsSending {
        func send(_ payload: AnalyticsPayload) {
            AppLog.analytics.debug("send \(payload.debugSummary, privacy: .public)")
        }
    }
#endif
