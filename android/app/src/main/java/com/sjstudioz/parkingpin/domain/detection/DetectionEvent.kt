package com.sjstudioz.parkingpin.domain.detection

import com.sjstudioz.parkingpin.domain.location.LocationSample
import com.sjstudioz.parkingpin.domain.trace.LocationQualityBucket

/**
 * Everything [ParkingDetectionEngine] can be told, in the normalized vocabulary of
 * `docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md` §2.
 *
 * No SDK type reaches here: Play services transitions arrive as [MotionDomainEvent] via
 * [ActivityTransitionMapper], Fused Location fixes as [LocationSample], and the Bluetooth
 * ACL broadcast as [CarLinkConnected] / [CarLinkDisconnected] after
 * [CarLinkPolicy] has decided the device is a car.
 *
 * Every event carries its own `atMillis`, and the engine reads time from **nothing else**.
 * That is what makes a fixture replay deterministic: the same JSON always produces the
 * same states, on both platforms, with no wall clock involved.
 */
sealed interface DetectionEvent {

    val atMillis: Long

    data class VehicleEnter(override val atMillis: Long) : DetectionEvent

    data class VehicleExit(override val atMillis: Long) : DetectionEvent

    data class WalkingEnter(override val atMillis: Long) : DetectionEvent

    data class StationaryEnter(override val atMillis: Long) : DetectionEvent

    data class StationaryExit(override val atMillis: Long) : DetectionEvent

    data class Location(val sample: LocationSample) : DetectionEvent {
        override val atMillis: Long get() = sample.atMillis
    }

    /**
     * A drop in location quality, with the §2 `fromBucket`/`toBucket` pair.
     *
     * The buckets are carried because §8 "GPS quality degraded near end" weighs losing the
     * sky, and only a drop **to `poor`** says that: a good-to-fair wobble happens on every
     * street and used to earn the weight here while iOS ignored it — the same field draft
     * scored MEDIUM on Android and LOW on iOS. A missing `toBucket` is read as `poor`, the
     * way iOS reads it, so an event from an older recorder still counts.
     *
     * No Android adapter emits this — Fused Location reports accuracies, not transitions —
     * and iOS feeds none to its engine either; on device both engines derive the same
     * evidence from the fixes themselves, as an accepted `poor` fix whose predecessor was
     * `good` or `fair` (docs/05 §8b). It stays in the vocabulary because the fixtures,
     * which are recorded traces, spell it out, and both engines read either form into one
     * "fell into poor" time.
     */
    data class LocationQualityDegraded(
        override val atMillis: Long,
        val fromBucket: LocationQualityBucket? = null,
        val toBucket: LocationQualityBucket? = null,
    ) : DetectionEvent {
        /** Whether this drop lost the sky, which is the only drop §8 weighs. */
        val reachedPoor: Boolean get() = toBucket == null || toBucket == LocationQualityBucket.POOR
    }

    /**
     * §3a "The car link".
     *
     * Which *kind* of link it was — Bluetooth car audio, or Android Auto / CarPlay
     * projection — is deliberately not carried. §3a puts both behind the single
     * `car_projection_disconnected` reason code and says the kind belongs in §8 weighting,
     * and §8 does not weigh it yet. Android has one producer today ([CarLinkReceiver]); the
     * distinction earns a field when something reads it.
     */
    data class CarLinkConnected(override val atMillis: Long) : DetectionEvent

    data class CarLinkDisconnected(override val atMillis: Long) : DetectionEvent

    /**
     * "Nothing happened, and time passed" — an invitation to re-judge §3a's windows.
     *
     * It is **not** the only thing that fires a timeout. Every event is settled against its
     * own timestamp — ingest, windows, edge, windows (docs/05 §3a "The windows are judged
     * before the edge too") — so a drive that keeps producing events needs no tick at all.
     * The tick exists for the drive that produces none: underground, no fixes, no transition
     * delivered. [com.sjstudioz.parkingpin.detection.DrivingLocationService] sends one a
     * minute while, and only while, the bounded capture runs; fixtures use it to state that
     * time passed.
     *
     * Ingesting the event's evidence before judging the windows is what keeps
     * `subway_commute_underground` a drive: a fix that lands more than `movementIdleWindow`
     * after the last one is movement continuing, not a parking.
     */
    data class TimerTick(override val atMillis: Long) : DetectionEvent

    data class UserConfirmedParking(override val atMillis: Long) : DetectionEvent

    data class UserRejectedParking(override val atMillis: Long) : DetectionEvent

    /**
     * The user saved a parking themselves — from the home screen, not by answering a
     * candidate (docs/05_PARKING_DETECTION_ENGINE.md §11c, contract §2 `user_saved`).
     *
     * Not a [UserConfirmedParking]: that one answers a prompt and means nothing outside
     * `CANDIDATE_PENDING`. This one arrives in any state and moves every one of them to
     * `PARKED`, because §11 only watches for a departure from there — and a parking the
     * engine was never told about is one no drive away could ever end.
     */
    data class UserSavedParking(override val atMillis: Long) : DetectionEvent

    /**
     * The user answered a departure proposal with `아직 주차 중` (docs/05 §11a, contract §2
     * `user_kept_parking`, DECIDED 2026-09-29).
     *
     * The same move as [UserSavedParking] — every state goes to `PARKED` and the drive the
     * departure opened is dropped silently — except that no record is written: the car is
     * still where the open record says it is.
     */
    data class UserKeptParking(override val atMillis: Long) : DetectionEvent

    /**
     * The user switched Smart Detection off (docs/05 §11 / §3a rule 4) — iOS's
     * `endDrivingSession(reason: .smartDetectionDisabled)`.
     *
     * Not a sensor event and not a user answer: it drops whatever the engine was inferring and
     * decides nothing. A drive or a transition goes to `IDLE` with no candidate, a departure
     * or a get-in returns to `PARKED` ending nothing, and a stop-only candidate's resume window
     * closes with the candidate kept. Turning detection off never closes a parking and never
     * withdraws a candidate the user may still answer.
     */
    data class SmartDetectionDisabled(override val atMillis: Long) : DetectionEvent
}

