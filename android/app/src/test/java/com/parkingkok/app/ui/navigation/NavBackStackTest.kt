package com.parkingkok.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell's back behaviour.
 *
 * This is the payoff for hand-rolling the stack: back navigation is a pure function, so
 * the cases that actually bite — a double tap, back at the root, a stack restored from a
 * bundle written by an older build — are ordinary assertions rather than UI tests.
 */
class NavBackStackTest {

    @Test
    fun `starts at home with nowhere to go back to`() {
        val stack = NavBackStack.rootedAtHome()

        assertEquals(ParkingkokRoute.Home, stack.current)
        assertFalse(stack.canGoBack)
    }

    @Test
    fun `back at the root stays at the root`() {
        val stack = NavBackStack.rootedAtHome().pop()

        assertEquals(ParkingkokRoute.Home, stack.current)
    }

    @Test
    fun `push then pop returns where it started`() {
        val stack = NavBackStack.rootedAtHome().push(ParkingkokRoute.Settings)

        assertEquals(ParkingkokRoute.Settings, stack.current)
        assertTrue(stack.canGoBack)
        assertEquals(ParkingkokRoute.Home, stack.pop().current)
    }

    @Test
    fun `a double tap does not stack the same screen twice`() {
        val stack = NavBackStack.rootedAtHome()
            .push(ParkingkokRoute.History)
            .push(ParkingkokRoute.History)

        assertEquals(2, stack.entries.size)
        assertEquals(ParkingkokRoute.Home, stack.pop().current)
    }

    @Test
    fun `two different records are two different screens`() {
        val stack = NavBackStack.rootedAtHome()
            .push(ParkingkokRoute.Detail("a"))
            .push(ParkingkokRoute.Detail("b"))

        assertEquals(3, stack.entries.size)
        assertEquals(ParkingkokRoute.Detail("a"), stack.pop().current)
    }

    @Test
    fun `replaceTop swaps the screen without deepening the stack`() {
        val stack = NavBackStack.rootedAtHome()
            .push(ParkingkokRoute.ManualEntry)
            .replaceTop(ParkingkokRoute.History)

        assertEquals(2, stack.entries.size)
        assertEquals(ParkingkokRoute.History, stack.current)
        assertEquals(ParkingkokRoute.Home, stack.pop().current)
    }

    @Test
    fun `popToRoot unwinds everything`() {
        val stack = NavBackStack.rootedAtHome()
            .push(ParkingkokRoute.Settings)
            .push(ParkingkokRoute.Diagnostics)
            .popToRoot()

        assertEquals(ParkingkokRoute.Home, stack.current)
        assertFalse(stack.canGoBack)
    }

    @Test
    fun `a stack survives being saved and restored`() {
        val original = NavBackStack.rootedAtHome()
            .push(ParkingkokRoute.History)
            .push(ParkingkokRoute.Detail("record-42"))

        val restored = NavBackStack.decode(original.encode())

        assertEquals(original.entries, restored.entries)
    }

    @Test
    fun `every route survives the round trip`() {
        val routes = listOf(
            ParkingkokRoute.Home,
            ParkingkokRoute.ManualEntry,
            ParkingkokRoute.History,
            ParkingkokRoute.Settings,
            ParkingkokRoute.Diagnostics,
            ParkingkokRoute.Detail("id-with-dashes-1234"),
        )

        routes.forEach { route ->
            assertEquals(route, ParkingkokRouteCodec.decode(ParkingkokRouteCodec.encode(route)))
        }
    }

    @Test
    fun `a token this build no longer knows is dropped, not crashed on`() {
        // An app update can rename a route while a saved bundle still names the old one.
        val restored = NavBackStack.decode(listOf("home", "gallery", "detail:a"))

        assertEquals(
            listOf(ParkingkokRoute.Home, ParkingkokRoute.Detail("a")),
            restored.entries,
        )
    }

    @Test
    fun `a stack that decodes to nothing falls back to home`() {
        assertEquals(NavBackStack.rootedAtHome().entries, NavBackStack.decode(emptyList()).entries)
        assertEquals(
            NavBackStack.rootedAtHome().entries,
            NavBackStack.decode(listOf("gallery", "widget")).entries,
        )
    }

    @Test
    fun `a detail token with no id is not a destination`() {
        assertEquals(null, ParkingkokRouteCodec.decode("detail:"))
    }
}
