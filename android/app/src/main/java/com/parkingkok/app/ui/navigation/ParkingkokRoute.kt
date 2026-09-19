package com.parkingkok.app.ui.navigation

/**
 * Every place the app can be.
 *
 * The shell is a plain stack — Home at the root, everything else pushed on top — so it is
 * modelled as a list of these rather than with a navigation library. Six flat
 * destinations and one argument do not need a graph, and this way the back behaviour is
 * an ordinary function that a unit test can drive, which a `NavController` is not.
 * Revisit that trade if the graph grows nested or animated.
 */
sealed interface ParkingkokRoute {

    /** `01-home-main.png`. Active parking, the floor keys, the primary actions. */
    data object Home : ParkingkokRoute

    /**
     * The manual entry form — FR-001. Reached from home when nothing is parked, and from
     * the confirmation screen's `직접 입력`.
     *
     * [candidateId] is what tells the two apart. Null is an ordinary manual save; set, the
     * same form confirms that candidate instead (docs/10_DESIGN_UX_SPEC.md §7a: "직접 입력
     * opens the existing manual entry ... and saving there confirms"). One screen rather
     * than two, because a second copy of this form would drift from the first.
     */
    data class ManualEntry(val candidateId: String? = null) : ParkingkokRoute

    /**
     * The candidate confirmation screen (docs/10_DESIGN_UX_SPEC.md §7a).
     *
     * Pushed, never presented as a dialog — §7a is explicit that a guess the user may not
     * want to answer must be dismissible with an ordinary back.
     */
    data class Confirm(val candidateId: String) : ParkingkokRoute

    /** `03-parking-detail.png`. */
    data class Detail(val recordId: String) : ParkingkokRoute

    /** `04-history-list.png`. */
    data object History : ParkingkokRoute

    /** `05-settings.png`. */
    data object Settings : ParkingkokRoute

    /** The P0 detection diagnostics screen, behind Settings -> 개발자. */
    data object Diagnostics : ParkingkokRoute
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
internal object ParkingkokRouteCodec {

    private const val DETAIL_PREFIX = "detail:"
    private const val CONFIRM_PREFIX = "confirm:"
    private const val MANUAL_CONFIRM_PREFIX = "manual:"

    fun encode(route: ParkingkokRoute): String = when (route) {
        ParkingkokRoute.Home -> "home"
        ParkingkokRoute.History -> "history"
        ParkingkokRoute.Settings -> "settings"
        ParkingkokRoute.Diagnostics -> "diagnostics"
        is ParkingkokRoute.ManualEntry ->
            route.candidateId?.let { MANUAL_CONFIRM_PREFIX + it } ?: "manual"
        is ParkingkokRoute.Detail -> DETAIL_PREFIX + route.recordId
        is ParkingkokRoute.Confirm -> CONFIRM_PREFIX + route.candidateId
    }

    fun decode(value: String): ParkingkokRoute? = when {
        value == "home" -> ParkingkokRoute.Home
        value == "manual" -> ParkingkokRoute.ManualEntry()
        value == "history" -> ParkingkokRoute.History
        value == "settings" -> ParkingkokRoute.Settings
        value == "diagnostics" -> ParkingkokRoute.Diagnostics
        value.startsWith(DETAIL_PREFIX) ->
            value.removePrefix(DETAIL_PREFIX).takeIf { it.isNotEmpty() }
                ?.let { ParkingkokRoute.Detail(it) }
        value.startsWith(CONFIRM_PREFIX) ->
            value.removePrefix(CONFIRM_PREFIX).takeIf { it.isNotEmpty() }
                ?.let { ParkingkokRoute.Confirm(it) }
        value.startsWith(MANUAL_CONFIRM_PREFIX) ->
            value.removePrefix(MANUAL_CONFIRM_PREFIX).takeIf { it.isNotEmpty() }
                ?.let { ParkingkokRoute.ManualEntry(it) }
        else -> null
    }
}
