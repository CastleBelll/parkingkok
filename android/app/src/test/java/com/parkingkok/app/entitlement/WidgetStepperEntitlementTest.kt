package com.parkingkok.app.entitlement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7a: "Tests must cover both branches".
 *
 * Until M6 there is no subscription, so the shipped build must be read-only while the
 * interactive path stays exercisable on DEV and STAGING.
 */
class WidgetStepperEntitlementTest {

    @Test
    fun `dev and staging may step the floor from the widget`() {
        assertTrue(isWidgetStepperEntitled(debuggable = true))
    }

    @Test
    fun `a production build is read-only`() {
        assertFalse(isWidgetStepperEntitled(debuggable = false))
    }
}
