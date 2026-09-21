package com.sjstudioz.parkingpin.ui.navigation

/**
 * An immutable back stack.
 *
 * Every operation returns a new stack, so the whole navigation state is one value that
 * Compose can hold in `rememberSaveable` and a test can assert on directly.
 */
@JvmInline
value class NavBackStack private constructor(val entries: List<ParkingpinRoute>) {

    /** Where the user is. Never empty — [ParkingpinRoute.Home] is the floor of the stack. */
    val current: ParkingpinRoute get() = entries.last()

    val canGoBack: Boolean get() = entries.size > 1

    /**
     * How deep the user is. 1 at the root.
     *
     * The shell compares it across a navigation to tell a push from a pop, which is what
     * decides which way the screen transition runs.
     */
    val depth: Int get() = entries.size

    /**
     * Pushes [route], unless it is already on top.
     *
     * Double-tapping a row is the common way to end up with the same screen twice, and
     * requiring a second back press to undo an accident is not navigation the user asked
     * for.
     */
    fun push(route: ParkingpinRoute): NavBackStack =
        if (current == route) this else NavBackStack(entries + route)

    /** Drops the top entry, or stays put at the root. */
    fun pop(): NavBackStack =
        if (canGoBack) NavBackStack(entries.dropLast(1)) else this

    /**
     * Replaces the top entry with [route].
     *
     * Used when one screen finishes into another — saving on the manual form lands on the
     * home screen, and pressing back from there should leave the app rather than reopen
     * the form the user just completed.
     */
    fun replaceTop(route: ParkingpinRoute): NavBackStack =
        NavBackStack(entries.dropLast(1) + route)

    /** Unwinds to the root. */
    fun popToRoot(): NavBackStack = NavBackStack(listOf(entries.first()))

    internal fun encode(): List<String> = entries.map(ParkingpinRouteCodec::encode)

    companion object {
        fun rootedAtHome(): NavBackStack = NavBackStack(listOf(ParkingpinRoute.Home))

        /**
         * Home with the confirmation screen on top of it — where a tapped candidate
         * notification lands (docs/05_PARKING_DETECTION_ENGINE.md §10a).
         *
         * Home is underneath rather than replaced, so back from a notification behaves
         * like back from anywhere else: it leaves the guess unanswered and shows the app,
         * rather than closing it.
         */
        fun openingCandidate(candidateId: String): NavBackStack =
            NavBackStack(listOf(ParkingpinRoute.Home, ParkingpinRoute.Confirm(candidateId)))

        /**
         * Restores a stack saved by [encode], skipping tokens this build no longer knows.
         * A stack that decodes to nothing falls back to the root.
         */
        internal fun decode(values: List<String>): NavBackStack {
            val routes = values.mapNotNull(ParkingpinRouteCodec::decode)
            return if (routes.isEmpty()) rootedAtHome() else NavBackStack(routes)
        }
    }
}
