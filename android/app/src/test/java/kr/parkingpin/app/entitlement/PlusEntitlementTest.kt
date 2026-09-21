package kr.parkingpin.app.entitlement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/08 §3's entitlement model, and the free-launch stub standing in for M6. */
class PlusEntitlementTest {

    @Test
    fun `a paying subscriber keeps their features while the store retries`() {
        // Arrange — the two states a lapsing subscriber passes through.
        val stillPaying = listOf(PlusEntitlement.GRACE, PlusEntitlement.BILLING_ISSUE)

        // Act & Assert — §3: access continues, or the app takes Plus away from someone
        // whose card simply expired.
        stillPaying.forEach { assertTrue(it.name, it.allowsPlusFeatures) }
    }

    @Test
    fun `an unresolved entitlement fails closed`() {
        // A wrong false costs a disabled control; a wrong true gives a paid feature away.
        assertFalse(PlusEntitlement.UNKNOWN.allowsPlusFeatures)
        assertFalse(PlusEntitlement.FREE.allowsPlusFeatures)
        assertFalse(PlusEntitlement.EXPIRED.allowsPlusFeatures)
    }

    @Test
    fun `the release build is free until M6 replaces the source`() {
        // Arrange, Act & Assert — the whole point of shipping free first (docs/08 §1a).
        assertEquals(PlusEntitlement.FREE, plusEntitlement(debuggable = false))
        assertFalse(isWidgetStepperEntitled(debuggable = false))
    }

    @Test
    fun `DEV and STAGING carry Plus so the paid paths stay testable`() {
        assertEquals(PlusEntitlement.PLUS_ACTIVE, plusEntitlement(debuggable = true))
        assertTrue(isWidgetStepperEntitled(debuggable = true))
    }

    @Test
    fun `every state answers the one question`() {
        // A state added later without a branch would not compile; this catches a state
        // added with the wrong one by making the whole table visible in one place.
        assertEquals(
            listOf(false, false, true, true, true, false),
            PlusEntitlement.entries.map { it.allowsPlusFeatures },
        )
    }
}
