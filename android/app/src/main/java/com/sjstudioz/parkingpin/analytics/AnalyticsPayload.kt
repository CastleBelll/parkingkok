package com.sjstudioz.parkingpin.analytics

/**
 * A value an analytics parameter may hold.
 *
 * **Three subtypes, and `Double` is deliberately not one of them.** A latitude is a
 * `Double`; with no subtype to put one in, docs/17 §3's forbidden coordinates cannot reach
 * a parameter even through a mistake in the mapper below. Buckets exist precisely so that
 * no continuous quantity ever needs to travel.
 */
sealed interface AnalyticsValue {

    data class Text(val value: String) : AnalyticsValue

    data class Count(val value: Int) : AnalyticsValue

    data class Flag(val value: Boolean) : AnalyticsValue
}

/**
 * One event flattened into the `(name, parameters)` shape every analytics transport
 * speaks — and the only thing an [AnalyticsSink] adapter ever sees.
 *
 * **The constructor is private: a payload can be built only from an [AnalyticsEvent].**
 * That is docs/07's "no `logEvent(name, params)`" expressed in code — there is no seam
 * anywhere between a call site and Firebase where a key could be invented, so the day
 * someone wants to attach a floor there is nothing to attach it to.
 */
class AnalyticsPayload private constructor(
    val name: String,
    val parameters: Map<String, AnalyticsValue>,
    /**
     * Transport metadata from the injected clock, and deliberately **not** a parameter:
     * docs/17 §3 fixes the allowed property list and a timestamp is not on it. An adapter
     * may use this for ordering or backdating; it never becomes an event property.
     */
    val occurredAtMillis: Long,
) {

    /**
     * Stable rendering for the debug sink and for failure messages. Keys sorted so two
     * runs of the same event read identically.
     */
    val debugSummary: String
        get() = parameters.keys.sorted()
            .joinToString(prefix = "$name {", postfix = "}") { key -> "$key=${describe(parameters.getValue(key))}" }

    override fun equals(other: Any?): Boolean = other is AnalyticsPayload &&
        name == other.name &&
        parameters == other.parameters &&
        occurredAtMillis == other.occurredAtMillis

    override fun hashCode(): Int = (name.hashCode() * 31 + parameters.hashCode()) * 31 + occurredAtMillis.hashCode()

    override fun toString(): String = debugSummary

    private fun describe(value: AnalyticsValue): String = when (value) {
        is AnalyticsValue.Text -> value.value
        is AnalyticsValue.Count -> value.value.toString()
        is AnalyticsValue.Flag -> value.value.toString()
    }

    companion object {

        /** docs/17 §2: "Properties always include `platform` where useful." */
        const val PLATFORM: String = "android"

        fun of(event: AnalyticsEvent, occurredAtMillis: Long): AnalyticsPayload {
            val parameters = buildMap {
                put(Key.PLATFORM, AnalyticsValue.Text(PLATFORM))
                when (event) {
                    // docs/17 §3 allows these nothing beyond `platform`, so they say
                    // nothing else.
                    AnalyticsEvent.OnboardingCompleted,
                    AnalyticsEvent.ParkingManualSaved,
                    AnalyticsEvent.PaywallViewed,
                    AnalyticsEvent.ReferralShared,
                    AnalyticsEvent.ReferralRedeemed,
                    -> Unit

                    is AnalyticsEvent.PermissionMotionResult ->
                        put(Key.RESULT, AnalyticsValue.Text(event.result.wireValue))

                    is AnalyticsEvent.PermissionLocationLevel ->
                        put(Key.LEVEL, AnalyticsValue.Text(event.level.wireValue))

                    is AnalyticsEvent.SmartDetectionEnabled ->
                        put(Key.ENABLED, AnalyticsValue.Flag(event.enabled))

                    is AnalyticsEvent.ParkingCandidateCreated -> putDetection(event.properties)
                    is AnalyticsEvent.ParkingCandidateConfirmed -> putDetection(event.properties)
                    is AnalyticsEvent.ParkingCandidateRejected -> putDetection(event.properties)

                    // No confidence to report — an auto end is not a candidate — but
                    // docs/17 §4 wants the engine version behind it, so the constant is
                    // stamped here.
                    AnalyticsEvent.ParkingAutoEnd ->
                        put(Key.DETECTOR_VERSION, AnalyticsValue.Count(DetectorVersion.CURRENT))

                    is AnalyticsEvent.WidgetFloorChanged ->
                        put(Key.DIRECTION, AnalyticsValue.Text(event.direction.wireValue))

                    is AnalyticsEvent.PurchaseCompleted ->
                        put(Key.STORE, AnalyticsValue.Text(event.store.wireValue))
                }
            }
            return AnalyticsPayload(event.name, parameters, occurredAtMillis)
        }

        private fun MutableMap<String, AnalyticsValue>.putDetection(properties: DetectionProperties) {
            put(Key.CONFIDENCE_BUCKET, AnalyticsValue.Text(properties.confidenceBucket.name.lowercase()))
            properties.driveDurationBucket?.let { put(Key.DRIVE_DURATION_BUCKET, AnalyticsValue.Text(it.wireValue)) }
            properties.distanceBucket?.let { put(Key.DISTANCE_BUCKET, AnalyticsValue.Text(it.wireValue)) }
            properties.accuracyBucket?.let { put(Key.ACCURACY_BUCKET, AnalyticsValue.Text(it.name.lowercase())) }
            put(Key.WALKING_EVIDENCE, AnalyticsValue.Flag(properties.walkingEvidence))
            put(Key.GPS_DEGRADATION, AnalyticsValue.Flag(properties.gpsDegradation))
            put(Key.OPTIONAL_VEHICLE_SIGNAL, AnalyticsValue.Flag(properties.optionalVehicleSignal))
            put(Key.DETECTOR_VERSION, AnalyticsValue.Count(properties.detectorVersion))
        }
    }

    /**
     * Every parameter key this contract can produce.
     *
     * `snake_case` rather than docs/17 §3's camelCase field names because Firebase
     * Analytics restricts parameter names to letters, digits and underscores; the two
     * platforms must agree on these strings, so they are listed rather than derived.
     */
    object Key {
        const val PLATFORM: String = "platform"
        const val RESULT: String = "result"
        const val LEVEL: String = "level"
        const val ENABLED: String = "enabled"
        const val DIRECTION: String = "direction"
        const val STORE: String = "store"
        const val CONFIDENCE_BUCKET: String = "confidence_bucket"
        const val DRIVE_DURATION_BUCKET: String = "drive_duration_bucket"
        const val DISTANCE_BUCKET: String = "distance_bucket"
        const val ACCURACY_BUCKET: String = "accuracy_bucket"
        const val WALKING_EVIDENCE: String = "walking_evidence"
        const val GPS_DEGRADATION: String = "gps_degradation"
        const val OPTIONAL_VEHICLE_SIGNAL: String = "optional_vehicle_signal"
        const val DETECTOR_VERSION: String = "detector_version"

        /**
         * The allowlist, so a test can hold every payload against it rather than trusting
         * a reading of the `when` above.
         */
        val ALL: Set<String> = setOf(
            PLATFORM, RESULT, LEVEL, ENABLED, DIRECTION, STORE,
            CONFIDENCE_BUCKET, DRIVE_DURATION_BUCKET, DISTANCE_BUCKET, ACCURACY_BUCKET,
            WALKING_EVIDENCE, GPS_DEGRADATION, OPTIONAL_VEHICLE_SIGNAL, DETECTOR_VERSION,
        )
    }
}
