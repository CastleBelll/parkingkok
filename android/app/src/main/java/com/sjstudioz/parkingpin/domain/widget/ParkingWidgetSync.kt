package com.sjstudioz.parkingpin.domain.widget

import com.sjstudioz.parkingpin.domain.parking.ParkingRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Where a rendered projection comes to rest.
 *
 * An interface rather than a direct Glance call so [ParkingWidgetSync] — which holds the
 * rule that Room is canonical — stays on the JVM and out of the widget toolkit
 * (docs/03_SYSTEM_ARCHITECTURE.md §5).
 */
interface WidgetProjectionStore {

    /** Writes [projection] to every placed widget and asks the host to redraw. */
    suspend fun write(projection: ParkingWidgetProjection)
}

/**
 * Keeps the widget projection equal to what Room says (docs/06 §4, §7, §8).
 *
 * This is the "repository/application layer" docs/06 §7 requires the Glance callback to
 * delegate to. The widget never derives a projection itself, so there is exactly one
 * place where a session becomes a snapshot.
 */
class ParkingWidgetSync(
    private val repository: ParkingRepository,
    private val store: WidgetProjectionStore,
    private val stepperEntitled: Boolean,
) {

    /**
     * Rebuilds the projection from the canonical record, once.
     *
     * This is docs/06 §8's startup repair: the projection is a pure function of the open
     * record, so a snapshot left behind by a session that has since been completed — or
     * by one deleted outright — is replaced with [ParkingWidgetProjection.Empty] rather
     * than lingering as a stale active parking. It is also what a newly placed widget
     * needs, since its state file starts empty.
     */
    suspend fun refresh() {
        store.write(ParkingWidgetProjection.of(repository.findActive(), stepperEntitled))
    }

    /**
     * Pushes a fresh projection on every change to the open record, for as long as the
     * caller's scope lives.
     *
     * Collecting one flow is what keeps the widget honest without every mutating use case
     * having to remember the widget exists. The first emission doubles as [refresh], so a
     * caller that starts here does not need both.
     *
     * `distinctUntilChanged` on the projection rather than on the record: a photo attached
     * or a memo edited changes the record but nothing the widget draws, and a redraw the
     * host did not need is battery spent for no pixels.
     */
    suspend fun keepInSync() {
        repository.observeActive()
            .map { ParkingWidgetProjection.of(it, stepperEntitled) }
            .distinctUntilChanged()
            .collect(store::write)
    }
}
