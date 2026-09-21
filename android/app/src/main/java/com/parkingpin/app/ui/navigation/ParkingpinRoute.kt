package com.parkingpin.app.ui.navigation

/**
 * Every place the app can be.
 *
 * The shell is a plain stack — Home at the root, everything else pushed on top — so it is
 * modelled as a list of these rather than with a navigation library. Six flat
 * destinations and one argument do not need a graph, and this way the back behaviour is
 * an ordinary function that a unit test can drive, which a `NavController` is not.
 * Revisit that trade if the graph grows nested or animated.
 */
sealed interface ParkingpinRoute {

    /** `01-home-main.png`. Active parking, the floor keys, the primary actions. */
    data object Home : ParkingpinRoute

    /**
     * The manual entry form — FR-001. Reached from home when nothing is parked, and from
     * the confirmation screen's `직접 입력`.
     *
     * [candidateId] is what tells the two apart. Null is an ordinary manual save; set, the
     * same form confirms that candidate instead (docs/10_DESIGN_UX_SPEC.md §7a: "직접 입력
     * opens the existing manual entry ... and saving there confirms"). One screen rather
     * than two, because a second copy of this form would drift from the first.
     */
    data class ManualEntry(
        val candidateId: String? = null,
        /**
         * True when `사진으로 입력` took a photo of the pillar on the way here
         * (docs/10_DESIGN_UX_SPEC.md §7a, docs/02 §6a).
         *
         * A flag rather than the read itself: the photo is the one file the camera
         * writes, so the form can open it, read it and attach it without any of that
         * crossing a navigation argument — and nothing the recogniser saw is ever
         * encoded into the saved back stack.
         */
        val fromPillarPhoto: Boolean = false,
    ) : ParkingpinRoute

    /**
     * The candidate confirmation screen (docs/10_DESIGN_UX_SPEC.md §7a).
     *
     * Pushed, never presented as a dialog — §7a is explicit that a guess the user may not
     * want to answer must be dismissible with an ordinary back.
     */
    data class Confirm(val candidateId: String) : ParkingpinRoute

    /** `03-parking-detail.png`. */
    data class Detail(val recordId: String) : ParkingpinRoute

    /** `04-history-list.png`. */
    data object History : ParkingpinRoute

    /**
     * What the bell opens: the candidates the app has raised and what became of them
     * (docs/10_DESIGN_UX_SPEC.md §7b).
     *
     * A destination of its own rather than a section of Settings. §7b moved the bell off
     * the notification *switches* precisely because "the question people actually have is
     * 'something buzzed while I was driving, what was it?'", and the switches stay where
     * the rest of them live.
     */
    data object Notifications : ParkingpinRoute

    /** `05-settings.png`. */
    data object Settings : ParkingpinRoute

    /** The P0 detection diagnostics screen, behind Settings -> 개발자. */
    data object Diagnostics : ParkingpinRoute
}

/**
 * Turns routes into strings and back, so the stack can be handed to `rememberSaveable`
 * and survive process death (docs/01_PRODUCT_REQUIREMENTS.md §8: active state must be
 * recoverable after the process is killed).
 *
 * An unreadable token decodes to null and is dropped rather than throwing: the saved
 * bundle can outlive an app update that renamed a route, and landing on Home beats
 * crashing on resume.
 */
internal object ParkingpinRouteCodec {

    private const val DETAIL_PREFIX = "detail:"
    private const val CONFIRM_PREFIX = "confirm:"
    private const val MANUAL_CONFIRM_PREFIX = "manual:"
    private const val PILLAR = "pillar"
    private const val PILLAR_CONFIRM_PREFIX = "pillar:"

    fun encode(route: ParkingpinRoute): String = when (route) {
        ParkingpinRoute.Home -> "home"
        ParkingpinRoute.History -> "history"
        ParkingpinRoute.Notifications -> "notifications"
        ParkingpinRoute.Settings -> "settings"
        ParkingpinRoute.Diagnostics -> "diagnostics"
        is ParkingpinRoute.ManualEntry -> {
            val prefix = if (route.fromPillarPhoto) PILLAR_CONFIRM_PREFIX else MANUAL_CONFIRM_PREFIX
            route.candidateId?.let { prefix + it } ?: if (route.fromPillarPhoto) PILLAR else "manual"
        }
        is ParkingpinRoute.Detail -> DETAIL_PREFIX + route.recordId
        is ParkingpinRoute.Confirm -> CONFIRM_PREFIX + route.candidateId
    }

    fun decode(value: String): ParkingpinRoute? = when {
        value == "home" -> ParkingpinRoute.Home
        value == "manual" -> ParkingpinRoute.ManualEntry()
        value == PILLAR -> ParkingpinRoute.ManualEntry(fromPillarPhoto = true)
        value == "history" -> ParkingpinRoute.History
        value == "notifications" -> ParkingpinRoute.Notifications
        value == "settings" -> ParkingpinRoute.Settings
        value == "diagnostics" -> ParkingpinRoute.Diagnostics
        value.startsWith(DETAIL_PREFIX) ->
            value.removePrefix(DETAIL_PREFIX).takeIf { it.isNotEmpty() }
                ?.let { ParkingpinRoute.Detail(it) }
        value.startsWith(CONFIRM_PREFIX) ->
            value.removePrefix(CONFIRM_PREFIX).takeIf { it.isNotEmpty() }
                ?.let { ParkingpinRoute.Confirm(it) }
        value.startsWith(MANUAL_CONFIRM_PREFIX) ->
            value.removePrefix(MANUAL_CONFIRM_PREFIX).takeIf { it.isNotEmpty() }
                ?.let { ParkingpinRoute.ManualEntry(it) }
        value.startsWith(PILLAR_CONFIRM_PREFIX) ->
            value.removePrefix(PILLAR_CONFIRM_PREFIX).takeIf { it.isNotEmpty() }
                ?.let { ParkingpinRoute.ManualEntry(it, fromPillarPhoto = true) }
        else -> null
    }
}
