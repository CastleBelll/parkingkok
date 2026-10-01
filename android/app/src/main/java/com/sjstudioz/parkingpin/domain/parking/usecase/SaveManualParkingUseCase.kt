package com.sjstudioz.parkingpin.domain.parking.usecase

import com.sjstudioz.parkingpin.analytics.AnalyticsEvent
import com.sjstudioz.parkingpin.analytics.AnalyticsRecording
import com.sjstudioz.parkingpin.analytics.DisabledAnalyticsRecorder
import com.sjstudioz.parkingpin.core.Clock
import com.sjstudioz.parkingpin.domain.parking.FloorParser
import com.sjstudioz.parkingpin.domain.parking.ParkingFieldLimits
import com.sjstudioz.parkingpin.domain.parking.ParkingLocation
import com.sjstudioz.parkingpin.domain.parking.ParkingLocationProvider
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingRepository
import com.sjstudioz.parkingpin.domain.parking.ParkingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What the user typed on the manual entry form. Every field is optional. */
data class ManualParkingInput(
    val floorRaw: String? = null,
    val zone: String? = null,
    val spot: String? = null,
    val memo: String? = null,
)

/** Outcome of [SaveManualParkingUseCase]. */
sealed interface SaveManualParkingResult {

    /**
     * [endedPrevious] is the asked-about parking this save closed at its departure time
     * (docs/05 §11a), in the same write; null for an ordinary save.
     */
    data class Saved(
        val record: ParkingRecord,
        val endedPrevious: ParkingRecord? = null,
    ) : SaveManualParkingResult

    /**
     * A parking session is already open, so nothing was written.
     *
     * Silently ending the previous one would throw away a record the user never asked to
     * close; silently opening a second would leave two "active" cars. The screen decides.
     */
    data class AlreadyActive(val existing: ParkingRecord) : SaveManualParkingResult
}

/**
 * Starts a parking session from typed input — docs/01_PRODUCT_REQUIREMENTS.md FR-001.
 *
 * The rule this use case exists to guarantee is CLAUDE.md's "Manual parking은 항상 가능해야
 * 한다": with location, motion and notification permission all denied, the save still
 * succeeds. That is why [locationProvider] is consulted defensively — a provider that
 * throws (a `SecurityException` from a revoked permission is the realistic case) costs the
 * record its coordinates and nothing else.
 */
class SaveManualParkingUseCase(
    private val repository: ParkingRepository,
    private val locationProvider: ParkingLocationProvider,
    private val clock: Clock,
    private val idGenerator: () -> String,
    /**
     * docs/17 §2 `parking_manual_saved`. Defaulted so a composition without an analytics
     * stack — a test, a preview — keeps working; [DisabledAnalyticsRecorder] reports
     * nothing rather than pretending to.
     */
    private val analytics: AnalyticsRecording = DisabledAnalyticsRecorder,
    /**
     * Outlives the screen, because the fix does. Defaults to a scope of its own so a test
     * that does not care about the fix does not have to provide one.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /**
     * [ending] is the open parking a pending departure allows this save to close
     * (docs/05 §11a); without it an open parking refuses the save.
     */
    suspend operator fun invoke(
        input: ManualParkingInput,
        ending: ActiveParkingEnd? = null,
    ): SaveManualParkingResult {
        repository.findActive()
            ?.takeIf { it.id != ending?.recordId }
            ?.let { return SaveManualParkingResult.AlreadyActive(it) }

        val now = clock.nowEpochMillis()
        val record = ParkingRecord(
            id = idGenerator(),
            startedAtMillis = now,
            endedAtMillis = null,
            source = ParkingSource.MANUAL,
            confidenceBucket = null,
            location = captureLocation(),
            floor = FloorParser.parse(input.floorRaw),
            zone = ParkingFieldLimits.normalize(input.zone, ParkingFieldLimits.MAX_SHORT_FIELD),
            spot = ParkingFieldLimits.normalize(input.spot, ParkingFieldLimits.MAX_SHORT_FIELD),
            memo = ParkingFieldLimits.normalize(input.memo, ParkingFieldLimits.MAX_MEMO),
            photoRelativePath = null,
            createdAtMillis = now,
            updatedAtMillis = now,
            revision = 1,
        )
        val opened = when (val result = repository.openParkingRecord(record, ending, now)) {
            is OpenParkingResult.Blocked -> return SaveManualParkingResult.AlreadyActive(result.active)
            is OpenParkingResult.Opened -> result
        }
        // After the write, never before: an event for a save that failed would overstate
        // the feature. The payload is the event name and `platform` — floor, zone, spot and
        // memo are §3 forbidden and `AnalyticsEvent` gives them nowhere to go.
        analytics.record(AnalyticsEvent.ParkingManualSaved)
        attachCurrentFix(record.id, record.location)
        return SaveManualParkingResult.Saved(record, endedPrevious = opened.endedPrevious)
    }

    /**
     * Asks the OS where the car is and writes it onto the record already saved.
     *
     * On [scope] and not the caller's: the save has returned and the screen is closing, so
     * a job tied to it would be cancelled before the GPS answered. This is the part nobody
     * waits for — the record exists, and the coordinate improves it a second or two later.
     *
     * A fix that arrives after the parking was ended or deleted updates nothing:
     * [ParkingRepository.update] is a no-op for a record that is gone.
     */
    private fun attachCurrentFix(recordId: String, stored: ParkingLocation?) {
        scope.launch {
            val fix = runCatching { locationProvider.currentFix() }.getOrNull() ?: return@launch
            // A stored location that is already more accurate stays. The fix is this
            // moment's, so it wins ties on age.
            // A stored accuracy of null is a location that never said how good it was, and
            // a fix that states 20 m beats one that states nothing.
            //
            // Only a *recent* stored location can win on accuracy: an old one is a different
            // place, however precisely it was measured (audit 2026-10-01).
            val storedAccuracy = stored?.horizontalAccuracyM
            val fixAccuracy = fix.horizontalAccuracyM
            val storedIsRecent = stored != null &&
                fix.capturedAtMillis - stored.capturedAtMillis <= ParkingLocationProvider.MAX_SAVED_LOCATION_AGE_MILLIS
            if (storedIsRecent && storedAccuracy != null && fixAccuracy != null && storedAccuracy <= fixAccuracy) {
                return@launch
            }
            repository.update(recordId) { current ->
                // Still open, still this parking: a record the user has since ended keeps
                // the coordinates it ended with.
                if (current.endedAtMillis != null) current else current.copy(location = fix)
            }
        }
    }

    /**
     * The location, if there is one to be had.
     *
     * `runCatching` is deliberate and narrow: this is the one call in the save path that
     * touches a permission-guarded subsystem, and FR-001 says the save outranks it.
     */
    private suspend fun captureLocation(): ParkingLocation? =
        runCatching { locationProvider.lastReliableLocation() }.getOrNull()
}
