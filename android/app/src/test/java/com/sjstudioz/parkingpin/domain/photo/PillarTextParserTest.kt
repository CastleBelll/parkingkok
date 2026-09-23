package com.sjstudioz.parkingpin.domain.photo

import com.sjstudioz.parkingpin.domain.parking.FloorKind
import com.sjstudioz.parkingpin.domain.parking.FloorParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a photo of a pillar suggests (docs/02_PRODUCT_SCOPE_AND_FLOWS.md §6a).
 *
 * The recognised text is the input, so these are ordinary pure-function tests: the
 * recogniser itself is the device's and is not re-tested here.
 */
class PillarTextParserTest {

    // ── The floor comes from the existing parser (§6a, docs/02 §6) ───────────────────

    @Test
    fun `the three shapes docs 02 section 6 already accepts are the three it reads`() {
        // Arrange — the examples §6 lists, each on its own sign.
        val shapes = listOf("B3", "지하 3층", "3F")

        for (shape in shapes) {
            // Act
            val suggestion = PillarTextParser.parse(listOf(shape))

            // Assert — the raw text is handed back, and it is the raw text FloorParser
            // reads. Reusing the parser rather than re-deciding what a floor looks like
            // is the point: a change to §6 changes both at once.
            assertEquals(shape, suggestion.floorRaw)
            assertEquals(3, FloorParser.parse(suggestion.floorRaw)?.number)
        }
    }

    @Test
    fun `지하 3층 survives being written with a space in it`() {
        // Arrange — split on whitespace, `지하 3층` becomes `3층`: the third floor above
        // ground, three levels from where the car actually is.
        val suggestion = PillarTextParser.parse(listOf("지하 3층 A구역"))

        // Assert
        assertEquals(FloorKind.BASEMENT, FloorParser.parse(suggestion.floorRaw)?.kind)
        assertEquals(3, FloorParser.parse(suggestion.floorRaw)?.number)
    }

    @Test
    fun `a bare number is a bay, never a floor`() {
        // Arrange — FloorParser reads `142` as 142층 for a field a person typed. A wall
        // is covered in bay numbers, and a `B3` misread as `83` would be the 83rd floor.
        val suggestion = PillarTextParser.parse(listOf("142"))

        // Assert
        assertNull(suggestion.floorRaw)
        assertEquals("142", suggestion.spot)
    }

    @Test
    fun `text the floor parser cannot read is not offered as a floor`() {
        // Arrange — FloorParser keeps an unreadable value as FREE_TEXT, which is right
        // for something a person typed and wrong for every other word on a sign.
        val suggestion = PillarTextParser.parse(listOf("엘리베이터", "주차장 안내"))

        // Assert
        assertEquals(PillarSuggestion.NONE, suggestion)
    }

    // ── Zone and bay ────────────────────────────────────────────────────────────────

    @Test
    fun `a zone is read with or without the space before 구역`() {
        assertEquals("A구역", PillarTextParser.parse(listOf("A구역")).zone)
        assertEquals("A구역", PillarTextParser.parse(listOf("A 구역")).zone)
    }

    @Test
    fun `a bay number keeps its digits and drops the 번`() {
        assertEquals("142", PillarTextParser.parse(listOf("142번")).spot)
    }

    @Test
    fun `a whole pillar reads as all three`() {
        // Arrange — what the recogniser hands back from an ordinary sign.
        val lines = listOf("지하 3층", "A구역 142")

        // Act
        val suggestion = PillarTextParser.parse(lines)

        // Assert
        assertEquals("지하 3층", suggestion.floorRaw)
        assertEquals("A구역", suggestion.zone)
        assertEquals("142", suggestion.spot)
    }

    @Test
    fun `a partial read fills what parsed and leaves the rest blank`() {
        // §6a: "partial read → fill what parsed, leave the rest blank".
        val suggestion = PillarTextParser.parse(listOf("B2"))

        assertEquals("B2", suggestion.floorRaw)
        assertNull(suggestion.zone)
        assertNull(suggestion.spot)
    }

    @Test
    fun `a pillar number is not a floor, however confidently it is read`() {
        // The real read of a real B2 garage whose pillars are numbered B14-B17, verbatim
        // from the recogniser — including what it made of a neighbouring pillar's badge.
        // `B17` came back at confidence 1.00 and `B2` at 0.30, and iOS offered 지하 17층
        // for a car parked on B2.
        val suggestion = PillarTextParser.parse(
            listOf("C13 1", "B2", "B17", "B", "82", "B16", "BB15"),
        )

        assertEquals("B2", suggestion.floorRaw)
    }

    @Test
    fun `nothing deeper than a garage goes is offered`() {
        // Offering one is worse than offering nothing: the user has to notice it and undo
        // it, and a wrong floor is what the read was supposed to save them from.
        for (input in listOf("B17", "B11", "지하 15층", "82F")) {
            assertNull(input, PillarTextParser.parse(listOf(input)).floorRaw)
        }
    }

    @Test
    fun `the floors a garage actually has are still offered`() {
        for (input in listOf("B1", "B7", "B10", "지하 3층", "2F", "20F")) {
            assertNotNull(input, PillarTextParser.parse(listOf(input)).floorRaw)
        }
    }

    @Test
    fun `a badge whose B was read as an 8 is still the floor, when it repeats`() {
        // What a phone actually read of a B2 garage: `B2` never appeared, `82` did, four
        // times — once per pillar in frame.
        val suggestion = PillarTextParser.parse(
            listOf("10/ C13", "a", "82", "B17", "B", "82", "B16", "[", "82", "B B15", "814", "82"),
        )

        assertEquals("B2", suggestion.floorRaw)
    }

    @Test
    fun `a bay number that appears once is still a bay number`() {
        // What keeps the correction honest: without repetition, `82` on a pillar is the bay
        // it almost always is, and rewriting it would invent a floor.
        assertNull(PillarTextParser.parse(listOf("82", "A구역")).floorRaw)
    }

    @Test
    fun `a read that already says the floor does not need correcting`() {
        assertEquals("B2", PillarTextParser.parse(listOf("B2", "82", "82")).floorRaw)
    }

    @Test
    fun `the pillar's own number is the zone, when the photo says which pillar`() {
        val suggestion = PillarTextParser.parse(listOf("B2", "B17"))

        assertEquals("B2", suggestion.floorRaw)
        assertEquals("B17", suggestion.zone)
    }

    @Test
    fun `a frame full of pillars names none of them`() {
        // B14-B17 in one photo cannot say which one the car is at, and a confident wrong
        // pillar sends the user to the wrong end of the floor.
        val suggestion = PillarTextParser.parse(
            listOf("82", "B17", "82", "B16", "82", "B15", "82"),
        )

        assertEquals("B2", suggestion.floorRaw)
        assertNull(suggestion.zone)
        assertNull(suggestion.spot)
    }

    @Test
    fun `nothing recognised is nothing suggested`() {
        assertEquals(PillarSuggestion.NONE, PillarTextParser.parse(emptyList()))
        assertEquals(PillarSuggestion.NONE, PillarTextParser.parse(listOf("", "   ")))
    }
}
