package kr.parkingpin.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

        assertEquals(ParkingpinRoute.Home, stack.current)
        assertFalse(stack.canGoBack)
    }

    @Test
    fun `back at the root stays at the root`() {
        val stack = NavBackStack.rootedAtHome().pop()

        assertEquals(ParkingpinRoute.Home, stack.current)
    }

    @Test
    fun `push then pop returns where it started`() {
        val stack = NavBackStack.rootedAtHome().push(ParkingpinRoute.Settings)

        assertEquals(ParkingpinRoute.Settings, stack.current)
        assertTrue(stack.canGoBack)
        assertEquals(ParkingpinRoute.Home, stack.pop().current)
    }

    @Test
    fun `a double tap does not stack the same screen twice`() {
        val stack = NavBackStack.rootedAtHome()
            .push(ParkingpinRoute.History)
            .push(ParkingpinRoute.History)

        assertEquals(2, stack.entries.size)
        assertEquals(ParkingpinRoute.Home, stack.pop().current)
    }

    @Test
    fun `two different records are two different screens`() {
        val stack = NavBackStack.rootedAtHome()
            .push(ParkingpinRoute.Detail("a"))
            .push(ParkingpinRoute.Detail("b"))

        assertEquals(3, stack.entries.size)
        assertEquals(ParkingpinRoute.Detail("a"), stack.pop().current)
    }

    @Test
    fun `replaceTop swaps the screen without deepening the stack`() {
        val stack = NavBackStack.rootedAtHome()
            .push(ParkingpinRoute.ManualEntry())
            .replaceTop(ParkingpinRoute.History)

        assertEquals(2, stack.entries.size)
        assertEquals(ParkingpinRoute.History, stack.current)
        assertEquals(ParkingpinRoute.Home, stack.pop().current)
    }

    @Test
    fun `popToRoot unwinds everything`() {
        val stack = NavBackStack.rootedAtHome()
            .push(ParkingpinRoute.Settings)
            .push(ParkingpinRoute.Diagnostics)
            .popToRoot()

        assertEquals(ParkingpinRoute.Home, stack.current)
        assertFalse(stack.canGoBack)
    }

    @Test
    fun `a stack survives being saved and restored`() {
        val original = NavBackStack.rootedAtHome()
            .push(ParkingpinRoute.History)
            .push(ParkingpinRoute.Detail("record-42"))

        val restored = NavBackStack.decode(original.encode())

        assertEquals(original.entries, restored.entries)
    }

    @Test
    fun `every route survives the round trip`() {
        val routes = listOf(
            ParkingpinRoute.Home,
            ParkingpinRoute.ManualEntry(),
            ParkingpinRoute.ManualEntry("candidate-with-dashes-1234"),
            ParkingpinRoute.History,
            ParkingpinRoute.Settings,
            ParkingpinRoute.Diagnostics,
            ParkingpinRoute.Detail("id-with-dashes-1234"),
            ParkingpinRoute.Confirm("candidate-with-dashes-1234"),
        )

        routes.forEach { route ->
            assertEquals(route, ParkingpinRouteCodec.decode(ParkingpinRouteCodec.encode(route)))
        }
    }

    @Test
    fun `a token this build no longer knows is dropped, not crashed on`() {
        // An app update can rename a route while a saved bundle still names the old one.
        val restored = NavBackStack.decode(listOf("home", "gallery", "detail:a"))

        assertEquals(
            listOf(ParkingpinRoute.Home, ParkingpinRoute.Detail("a")),
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
        assertEquals(null, ParkingpinRouteCodec.decode("detail:"))
    }

    @Test
    fun `a tapped candidate notification opens the confirmation over home`() {
        // docs/05 §10a: the tap "opens the confirmation screen for that candidateId".
        val stack = NavBackStack.openingCandidate("candidate-1")

        assertEquals(ParkingpinRoute.Confirm("candidate-1"), stack.current)
        // Back leaves the guess unanswered and shows the app, rather than closing it.
        assertTrue(stack.canGoBack)
        assertEquals(ParkingpinRoute.Home, stack.pop().current)
    }

    @Test
    fun `a manual form opened to confirm a candidate is a different screen from a blank one`() {
        // They share a composable but not an identity: encoding one must not restore the
        // other, or a process death would turn a confirmation into a fresh manual save.
        assertNotEquals(ParkingpinRoute.ManualEntry(), ParkingpinRoute.ManualEntry("candidate-1"))
        assertEquals(
            ParkingpinRoute.ManualEntry("candidate-1"),
            ParkingpinRouteCodec.decode(ParkingpinRouteCodec.encode(ParkingpinRoute.ManualEntry("candidate-1"))),
        )
    }
}
