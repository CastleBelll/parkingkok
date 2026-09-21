package com.parkingpin.app.domain.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/01_PRODUCT_REQUIREMENTS.md FR-005: `B1..Bn`, `1F..nF`, free-text fallback. */
class FloorParserTest {

    @Test
    fun `parses basement in latin form`() {
        val floor = FloorParser.parse("B3")

        assertEquals(FloorKind.BASEMENT, floor?.kind)
        assertEquals(3, floor?.number)
        assertEquals("B3", floor?.displayLabel)
        assertEquals(-3, floor?.signedIndex)
    }

    @Test
    fun `parses ground in latin form`() {
        val floor = FloorParser.parse("7F")

        assertEquals(FloorKind.GROUND, floor?.kind)
        assertEquals(7, floor?.number)
        assertEquals("7F", floor?.displayLabel)
        assertEquals(7, floor?.signedIndex)
    }

    @Test
    fun `accepts lowercase and surrounding whitespace`() {
        assertEquals(FloorParser.parse("B3"), FloorParser.parse("  b3 ")?.copy(raw = "B3"))
        assertEquals(FloorKind.GROUND, FloorParser.parse(" 2f ")?.kind)
    }

    @Test
    fun `parses korean spellings so they keep the plus minus keys`() {
        assertEquals(FloorKind.BASEMENT, FloorParser.parse("지하 3층")?.kind)
        assertEquals(3, FloorParser.parse("지하3층")?.number)
        assertEquals(FloorKind.GROUND, FloorParser.parse("5층")?.kind)
        assertEquals(FloorKind.GROUND, FloorParser.parse("지상 5층")?.kind)
    }

    @Test
    fun `a bare number reads as a ground floor`() {
        val floor = FloorParser.parse("3")

        assertEquals(FloorKind.GROUND, floor?.kind)
        assertEquals(3, floor?.number)
    }

    @Test
    fun `keeps the raw text the user typed`() {
        assertEquals("지하 3층", FloorParser.parse("지하 3층")?.raw)
        assertEquals("B3", FloorParser.parse("  B3  ")?.raw)
    }

    @Test
    fun `falls back to free text and stays unsteppable`() {
        val floor = FloorParser.parse("옥상 주차장")

        assertEquals(FloorKind.FREE_TEXT, floor?.kind)
        assertNull(floor?.number)
        assertNull(floor?.signedIndex)
        assertFalse(floor?.isSteppable ?: true)
        assertEquals("옥상 주차장", floor?.displayLabel)
    }

    @Test
    fun `blank input means there is no floor at all`() {
        assertNull(FloorParser.parse(null))
        assertNull(FloorParser.parse(""))
        assertNull(FloorParser.parse("   "))
    }

    @Test
    fun `zero and out of range levels are free text because no such floor exists`() {
        assertEquals(FloorKind.FREE_TEXT, FloorParser.parse("B0")?.kind)
        assertEquals(FloorKind.FREE_TEXT, FloorParser.parse("0F")?.kind)
        assertEquals(FloorKind.FREE_TEXT, FloorParser.parse("B100")?.kind)
        assertEquals(FloorKind.FREE_TEXT, FloorParser.parse("120F")?.kind)
    }

    @Test
    fun `numeric floors are steppable and free text is not`() {
        assertTrue(FloorParser.parse("B2")?.isSteppable ?: false)
        assertTrue(FloorParser.parse("1F")?.isSteppable ?: false)
        assertFalse(FloorParser.parse("옥상")?.isSteppable ?: true)
    }

    @Test
    fun `stepping up from B1 lands on 1F because there is no zero floor`() {
        assertEquals("1F", FloorParser.step(FloorParser.parse("B1"), 1)?.displayLabel)
    }

    @Test
    fun `stepping down from 1F lands on B1`() {
        assertEquals("B1", FloorParser.step(FloorParser.parse("1F"), -1)?.displayLabel)
    }

    @Test
    fun `stepping moves within a sign`() {
        assertEquals("B2", FloorParser.step(FloorParser.parse("B3"), 1)?.displayLabel)
        assertEquals("B4", FloorParser.step(FloorParser.parse("B3"), -1)?.displayLabel)
        assertEquals("4F", FloorParser.step(FloorParser.parse("3F"), 1)?.displayLabel)
    }

    @Test
    fun `stepping free text does nothing`() {
        assertNull(FloorParser.step(FloorParser.parse("옥상"), 1))
        assertNull(FloorParser.step(null, 1))
    }

    @Test
    fun `stepping off either end of the ladder does nothing`() {
        assertNull(FloorParser.step(FloorParser.parse("B${FloorParser.MAX_LEVEL}"), -1))
        assertNull(FloorParser.step(FloorParser.parse("${FloorParser.MAX_LEVEL}F"), 1))
    }

    @Test
    fun `a stepped floor rewrites raw so the label follows the value`() {
        val stepped = FloorParser.step(FloorParser.parse("지하 3층"), 1)

        assertEquals("B2", stepped?.raw)
        assertEquals("B2", stepped?.displayLabel)
    }

    @Test
    fun `restores stored fields without re-parsing them`() {
        // A row whose raw text the current parser would read differently must keep the
        // reading it was saved with.
        val restored = FloorParser.fromStoredFields("3", FloorKind.BASEMENT.name, 3)

        assertEquals(FloorKind.BASEMENT, restored?.kind)
        assertEquals("B3", restored?.displayLabel)
    }

    @Test
    fun `a corrupt stored row degrades to free text instead of throwing`() {
        val restored = FloorParser.fromStoredFields("B3", FloorKind.BASEMENT.name, null)

        assertEquals(FloorKind.FREE_TEXT, restored?.kind)
        assertEquals("B3", restored?.displayLabel)
    }

    @Test
    fun `talkback reads a floor as words, not letters`() {
        assertEquals("지하 3층", FloorParser.parse("B3")?.spokenLabel)
        assertEquals("지상 2층", FloorParser.parse("2F")?.spokenLabel)
        assertEquals("옥상", FloorParser.parse("옥상")?.spokenLabel)
    }
}
