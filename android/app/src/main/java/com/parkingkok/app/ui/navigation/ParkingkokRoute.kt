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

    /** The manual entry form — FR-001. Reached from home when nothing is parked. */
    data object ManualEntry : ParkingkokRoute

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

    fun encode(route: ParkingkokRoute): String = when (route) {
        ParkingkokRoute.Home -> "home"
        ParkingkokRoute.ManualEntry -> "manual"
        ParkingkokRoute.History -> "history"
        ParkingkokRoute.Settings -> "settings"
        ParkingkokRoute.Diagnostics -> "diagnostics"
        is ParkingkokRoute.Detail -> DETAIL_PREFIX + route.recordId
    }

    fun decode(value: String): ParkingkokRoute? = when {
        value == "home" -> ParkingkokRoute.Home
        value == "manual" -> ParkingkokRoute.ManualEntry
        value == "history" -> ParkingkokRoute.History
        value == "settings" -> ParkingkokRoute.Settings
        value == "diagnostics" -> ParkingkokRoute.Diagnostics
        value.startsWith(DETAIL_PREFIX) ->
            value.removePrefix(DETAIL_PREFIX).takeIf { it.isNotEmpty() }
                ?.let { ParkingkokRoute.Detail(it) }
        else -> null
    }
}
