package kr.parkingpin.app.analytics

import kr.parkingpin.app.domain.parking.ConfidenceBucket
import kr.parkingpin.app.domain.trace.LocationQualityBucket

/** Records what a transport would have received. The only way to observe the gate. */
class RecordingAnalyticsSink : AnalyticsSink {

    private val received = mutableListOf<AnalyticsPayload>()

    val payloads: List<AnalyticsPayload> get() = received.toList()

    val names: List<String> get() = received.map { it.name }

    override fun send(payload: AnalyticsPayload) {
        received += payload
    }
}

/**
 * Records the events a feature asked for, above the consent gate.
 *
 * [RecordingAnalyticsSink] observes what a transport would receive and therefore also
 * observes consent; this observes the call site's own decision, which is what a feature
 * test is about. The gate itself has its own tests.
 */
class RecordingAnalytics : AnalyticsRecording {

    private val received = mutableListOf<AnalyticsEvent>()

    val events: List<AnalyticsEvent> get() = received.toList()

    val names: List<String> get() = received.map { it.name }

    override suspend fun record(event: AnalyticsEvent) {
        received += event
    }
}

/**
 * One sample of every event, so a contract test can walk the whole hierarchy. Sample values
 * are deliberately all-different, which is what lets the "no forbidden key" assertions see
 * every branch of the payload mapper.
 */
object EventSamples {

    val detection = DetectionProperties(
        confidenceBucket = ConfidenceBucket.MEDIUM,
        driveDurationBucket = DriveDurationBucket.MIN_5_15,
        distanceBucket = DistanceBucket.KM_1_5,
        accuracyBucket = LocationQualityBucket.FAIR,
        walkingEvidence = true,
        gpsDegradation = false,
        optionalVehicleSignal = true,
    )

    val all: List<AnalyticsEvent> = listOf(
        AnalyticsEvent.OnboardingCompleted,
        AnalyticsEvent.PermissionMotionResult(MotionPermissionResult.GRANTED),
        AnalyticsEvent.PermissionLocationLevel(LocationPermissionLevel.WHEN_IN_USE),
        AnalyticsEvent.SmartDetectionEnabled(enabled = true),
        AnalyticsEvent.ParkingCandidateCreated(detection),
        AnalyticsEvent.ParkingCandidateConfirmed(detection),
        AnalyticsEvent.ParkingCandidateRejected(detection),
        AnalyticsEvent.ParkingManualSaved,
        AnalyticsEvent.ParkingAutoEnd,
        AnalyticsEvent.WidgetFloorChanged(WidgetFloorDirection.DOWN),
        AnalyticsEvent.PaywallViewed,
        AnalyticsEvent.PurchaseCompleted(PurchaseStore.PLAY_STORE),
        AnalyticsEvent.ReferralShared,
        AnalyticsEvent.ReferralRedeemed,
    )
}
