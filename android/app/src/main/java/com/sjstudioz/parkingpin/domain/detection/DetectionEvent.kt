package com.sjstudioz.parkingpin.domain.detection

import com.sjstudioz.parkingpin.domain.location.LocationSample

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
     * A drop in location quality, as §2 reports it.
     *
     * It carries no buckets, because nothing acts on their values: §8 weighs *that* quality
     * degraded near the end of a drive, not by how much. The `fromBucket`/`toBucket` pair
     * belongs to the trace format, which is a recording that has to stay readable years
     * later, and lives there.
     *
     * No Android adapter emits this — Fused Location reports accuracies, not transitions —
     * so on this platform the engine derives the same conclusion from the fixes themselves
     * (see [TravelSession.lastQualityBucket]). It stays in the vocabulary because the
     * fixtures spell it out and because iOS has the event for real.
     */
    data class LocationQualityDegraded(override val atMillis: Long) : DetectionEvent

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
     * The only thing that fires a §3a **timeout**.
     *
     * The transition table mixes two kinds of elapsed-time rule and they must not be
     * treated alike:
     *
     * - *A condition that became true by holding* — `DRIVING_CANDIDATE -> DRIVING` after
     *   90 s of sustained vehicle activity, `PARKED -> DEPARTURE_CANDIDATE` once §11's two
     *   bars are cleared. These are evaluated on **every** event, because the answer is
     *   already true when we look and looking late cannot make it false.
     * - *A timeout — nothing happened for long enough* — `drivingCandidateWindow`,
     *   `movementIdleWindow`, `transitionWindow`, the 45-minute expiry. These fire **only
     *   here**, because a timeout that fired opportunistically on the next unrelated event
     *   would retroactively kill a session that had already earned its promotion.
     *
     * `subway_commute_underground` is the fixture that proves the distinction is load
     * bearing: `vehicle_enter` at t=6404 is followed by silence until t=6707 (303 s, past
     * `drivingCandidateWindow`) and the next fix is at t=7719 (1012 s, past
     * `movementIdleWindow`). Evaluated opportunistically, either gap discards a drive the
     * contract expects to end in `CANDIDATE_PENDING`. The fixture vocabulary has carried a
     * `timer_tick` event all along and none of the five committed fixtures uses one, which
     * is the same statement from the other side.
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
}

