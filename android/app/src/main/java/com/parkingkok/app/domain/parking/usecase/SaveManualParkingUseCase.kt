package com.parkingkok.app.domain.parking.usecase

import com.parkingkok.app.core.Clock
import com.parkingkok.app.domain.parking.FloorParser
import com.parkingkok.app.domain.parking.ParkingLocation
import com.parkingkok.app.domain.parking.ParkingLocationProvider
import com.parkingkok.app.domain.parking.ParkingRecord
import com.parkingkok.app.domain.parking.ParkingRepository
import com.parkingkok.app.domain.parking.ParkingSource

/** What the user typed on the manual entry form. Every field is optional. */
data class ManualParkingInput(
    val floorRaw: String? = null,
    val zone: String? = null,
    val spot: String? = null,
    val memo: String? = null,
)

/** Outcome of [SaveManualParkingUseCase]. */
sealed interface SaveManualParkingResult {

    data class Saved(val record: ParkingRecord) : SaveManualParkingResult

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
) {

    suspend operator fun invoke(input: ManualParkingInput): SaveManualParkingResult {
        repository.findActive()?.let { return SaveManualParkingResult.AlreadyActive(it) }

        val now = clock.nowEpochMillis()
        val record = ParkingRecord(
            id = idGenerator(),
            startedAtMillis = now,
            endedAtMillis = null,
            source = ParkingSource.MANUAL,
            confidenceBucket = null,
            location = captureLocation(),
            floor = FloorParser.parse(input.floorRaw),
            zone = input.zone.normalize(MAX_SHORT_FIELD),
            spot = input.spot.normalize(MAX_SHORT_FIELD),
            memo = input.memo.normalize(MAX_MEMO),
            photoRelativePath = null,
            createdAtMillis = now,
            updatedAtMillis = now,
            revision = 1,
        )
        repository.insert(record)
        return SaveManualParkingResult.Saved(record)
    }

    /**
     * The location, if there is one to be had.
     *
     * `runCatching` is deliberate and narrow: this is the one call in the save path that
     * touches a permission-guarded subsystem, and FR-001 says the save outranks it.
     */
    private suspend fun captureLocation(): ParkingLocation? =
        runCatching { locationProvider.lastReliableLocation() }.getOrNull()

    /**
     * Trims, drops empties, and truncates.
     *
     * FR-006 caps zone and spot at 40 characters. Truncating rather than rejecting keeps
     * a paste from blocking a save the user is making in a hurry in a car park.
     */
    private fun String?.normalize(maxLength: Int): String? =
        this?.trim()?.take(maxLength)?.takeIf { it.isNotEmpty() }

    private companion object {
        /** FR-006: zone and spot are each capped at 40 characters. */
        const val MAX_SHORT_FIELD = 40

        /** FR-006 does not size the memo; this is a storage bound, not a product rule. */
        const val MAX_MEMO = 200
    }
}
