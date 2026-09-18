package com.parkingkok.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import com.parkingkok.app.ParkingkokApplication
import com.parkingkok.app.domain.parking.usecase.AdjustParkingFloorUseCase

/**
 * The widget's `-` / `+` key (docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7a).
 *
 * docs/06 §7 requires the callback to delegate to the repository/application layer, so
 * everything below is a hand-off: [AdjustParkingFloorUseCase] performs the read-modify-write
 * under Room's transaction and `ParkingWidgetSync` writes the result back to the widgets.
 * No rule about floors or sessions lives in this file.
 *
 * ## What the tap carries
 *
 * A **delta**, plus the session the widget had rendered — never a resulting floor. §7a is
 * explicit about why: two taps landing together must move two floors, and a value would
 * have let the second write discard the first. The session id is what makes a tap that
 * arrives after the parking ended a dropped mutation rather than an edit to whatever
 * session came next; the use case addresses that id directly, so there is no window in
 * which it could resolve to another record.
 *
 * Every outcome ends in a refresh, including the ones that changed nothing: a delta the
 * floor domain rejects at the top or bottom of the ladder is a no-op that still reloads,
 * so the display snaps back to the true value instead of appearing to have moved.
 */
class ParkingWidgetFloorStepAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val container = ParkingkokApplication.containerOf(context) ?: return
        val sessionId = parameters[SessionIdKey]
        val delta = parameters[DeltaKey]

        // Entitlement is re-read here and not trusted from the rendered snapshot: a widget
        // drawn before the answer changed would otherwise keep a live key (§7a).
        if (sessionId != null && delta != null && container.isWidgetStepperEntitled) {
            AdjustParkingFloorUseCase(container.parkingRepository, container.clock)
                .invoke(delta = delta, sessionId = sessionId)
        }
        container.parkingWidgetSync.refresh()
    }

    companion object {

        private val SessionIdKey = ActionParameters.Key<String>("sessionId")
        private val DeltaKey = ActionParameters.Key<Int>("delta")

        /** The tap [sessionId]'s `-` or `+` key sends, carrying [delta] and not a floor. */
        fun action(sessionId: String, delta: Int): Action =
            actionRunCallback<ParkingWidgetFloorStepAction>(
                actionParametersOf(SessionIdKey to sessionId, DeltaKey to delta),
            )
    }
}
