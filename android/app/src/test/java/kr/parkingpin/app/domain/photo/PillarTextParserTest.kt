package kr.parkingpin.app.domain.photo

import kr.parkingpin.app.domain.parking.FloorKind
import kr.parkingpin.app.domain.parking.FloorParser
import org.junit.Assert.assertEquals
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
    fun `nothing recognised is nothing suggested`() {
        assertEquals(PillarSuggestion.NONE, PillarTextParser.parse(emptyList()))
        assertEquals(PillarSuggestion.NONE, PillarTextParser.parse(listOf("", "   ")))
    }
}
