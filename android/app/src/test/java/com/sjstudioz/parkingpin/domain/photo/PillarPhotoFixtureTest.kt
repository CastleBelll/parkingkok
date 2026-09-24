package com.sjstudioz.parkingpin.domain.photo

import com.sjstudioz.parkingpin.domain.parking.FloorKind
import com.sjstudioz.parkingpin.domain.parking.FloorParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Twelve real pillar photos, read by an on-device recogniser, with what the wall actually
 * said beside what came back.
 *
 * Every rule in [PillarTextParser] was written against a photo, and this is the set of
 * photos. Perfect reading is not achievable — §6a says so, and some of these twelve still
 * lose something — but a rule that fixes one wall and breaks another is caught here rather
 * than on the phone. Each row is the recogniser's verbatim output with the fraction of the
 * image height it filled.
 *
 * The same twelve are asserted on iOS in `PillarPhotoFixtureTests`.
 */
class PillarPhotoFixtureTest {

    /**
     * A close-up of a single B1 wall, where the badge's `B` came back as an `8`.
     *
     * The repetition rule cannot fire on one pillar. Size is what is left, and `81` filled
     * an eighth of the frame — this is the photo [PillarTextParser.LARGE_BADGE_HEIGHT]
     * exists for. Before it, the user was offered 자리 81 for a car on B1.
     */
    @Test
    fun `7B4E5633 - a lone 81 painted across the frame is B1`() {
        val suggestion = PillarTextParser.parse(listOf(PillarLine("81", 0.129)))

        assertEquals("B1", suggestion.floorRaw)
        assertNull(suggestion.spot)
    }

    /** A pillar that paints its letter above its number, returned as two rows. */
    @Test
    fun `77F9B13C - A over 47 is zone A and bay 47`() {
        val suggestion = PillarTextParser.parse(
            listOf(PillarLine("A", 0.140), PillarLine("47", 0.143)),
        )

        assertNull(suggestion.floorRaw)
        assertEquals("A", suggestion.zone)
        assertEquals("47", suggestion.spot)
    }

    /** Three bays in one frame — `02` in front of the camera, `03` and `04` down the row. */
    @Test
    fun `98E8104E - the nearest bay wins, not the first one read`() {
        val suggestion = PillarTextParser.parse(
            listOf(
                PillarLine("04", 0.031),
                PillarLine("03", 0.047),
                PillarLine("B2", 0.031),
                PillarLine("02 02", 0.136),
                PillarLine("B2", 0.074),
                PillarLine("B2", 0.082),
            ),
        )

        assertEquals("B2", suggestion.floorRaw)
        assertEquals("02", suggestion.spot)
    }

    /** The wide shot the earlier rules were written against, unchanged by the new ones. */
    @Test
    fun `41EECF59 - a repeated 82 is the floor and the nearest pillar is the zone`() {
        val suggestion = PillarTextParser.parse(
            listOf(
                PillarLine("10/ C13", 0.031),
                PillarLine("a", 0.027),
                PillarLine("82", 0.023),
                PillarLine("B17", 0.059),
                PillarLine("B", 0.047),
                PillarLine("82", 0.020),
                PillarLine("B16", 0.047),
                PillarLine("[", 0.031),
                PillarLine("82", 0.020),
                PillarLine("B B15", 0.051),
                PillarLine("814", 0.043),
                PillarLine("82", 0.027),
            ),
        )

        assertEquals("B2", suggestion.floorRaw)
        // The stray `B` is a fragment of a label already read, not a zone of its own.
        assertEquals("B17", suggestion.zone)
        assertNull("814 is B14, and B14 is not a floor either", suggestion.spot)
    }

    @Test
    fun `4BC91EF8 - B2 over bay 02, with a warning sign in frame`() {
        val suggestion = PillarTextParser.parse(
            listOf(
                PillarLine("주차장 바닥에 브레거를", 0.014),
                PillarLine("버려지 마셔요.", 0.012),
                PillarLine("B2", 0.152),
                PillarLine("B2 02", 0.063),
            ),
        )

        assertEquals("B2", suggestion.floorRaw)
        assertEquals("02", suggestion.spot)
    }

    @Test
    fun `98BBE445 - B118 read as B1 and 18 is a floor and a bay`() {
        val suggestion = PillarTextParser.parse(
            listOf(PillarLine("B1 18", 0.269), PillarLine("B1 191", 0.121)),
        )

        assertEquals("B1", suggestion.floorRaw)
        assertEquals("18", suggestion.spot)
    }

    @Test
    fun `63205636 - a wall that says B3 and nothing else`() {
        val suggestion = PillarTextParser.parse(listOf(PillarLine("B3", 0.114)))

        assertEquals(PillarSuggestion(floorRaw = "B3"), suggestion)
    }

    @Test
    fun `9B89C54F - a pillar numbered B35 states no floor, and B35 is not one`() {
        val suggestion = PillarTextParser.parse(
            listOf(
                PillarLine("B35", 0.039),
                PillarLine("PARKING", 0.031),
                PillarLine("보형자", 0.035),
            ),
        )

        assertNull(suggestion.floorRaw)
        assertEquals("B35", suggestion.zone)
    }

    @Test
    fun `94695E1F - B4F is B4, and the bay is the pillar's own number`() {
        // The wall paints `428` over `B4F`. `B4F` says basement and floor at once, which is
        // redundant and real; read as free text it threw away a floor stated plainly.
        val suggestion = PillarTextParser.parse(
            listOf(
                PillarLine("428", 0.034),
                PillarLine("B4F", 0.019),
                PillarLine("428", 0.012),
                PillarLine("그르타/", 0.041),
            ),
        )

        assertEquals("B4F", suggestion.floorRaw)
        assertEquals(4, FloorParser.parse("B4F")?.number)
        assertEquals(FloorKind.BASEMENT, FloorParser.parse("B4F")?.kind)
        assertEquals("428", suggestion.spot)
    }

    @Test
    fun `873CE149 - a photo with nothing readable in it is silence`() {
        assertEquals(PillarSuggestion.NONE, PillarTextParser.parse(emptyList<PillarLine>()))
    }

    /** Known incomplete, and kept as the honest record of it. */
    @Test
    fun `3B922445 - the wall says B1 over 27 and only the 27 was recognised`() {
        val suggestion = PillarTextParser.parse(listOf(PillarLine("27", 0.239)))

        // Nothing here can recover a floor that was never recognised: inventing one from a
        // bay number is precisely the misread §6a forbids. The bay is offered and the user
        // types the floor, which is the failure mode the feature is designed around.
        assertNull(suggestion.floorRaw)
        assertEquals("27", suggestion.spot)
    }

    /** Not a car park at all — a press photo that happened to be in the folder. */
    @Test
    fun `D336556B - a photo of something else offers no floor`() {
        val suggestion = PillarTextParser.parse(
            listOf(
                PillarLine("81", 0.020),
                PillarLine("09", 0.039),
                PillarLine("YONHAPNEWS", 0.051),
            ),
        )

        // The same `81` that is a floor when it fills the frame is not one at a fiftieth of
        // it. A suggested bay on a photo the user chose is harmless; a suggested floor is not.
        assertNull(suggestion.floorRaw)
    }
}

/**
 * The same twelve photos, read by **ML Kit on the phone** rather than by Vision on a Mac.
 *
 * Two recognisers see one wall differently, and the rules have to hold for both: ML Kit
 * recovered the `B1` Vision missed on 3B922445 and read a whole row of pillars as one
 * forty-character line on 41EECF59. These are the readings this device actually produced.
 * Asserted on iOS too, in `PillarPhotoCrossReaderFixtureTests`.
 */
class PillarPhotoCrossReaderFixtureTest {

    @Test
    fun `3B922445 - the floor Vision missed is read here, and it is the floor`() {
        val suggestion = PillarTextParser.parse(
            listOf(PillarLine("B1", 0.114), PillarLine("27", 0.277)),
        )

        assertEquals("B1", suggestion.floorRaw)
        assertEquals("27", suggestion.spot)
    }

    @Test
    fun `41EECF59 - a whole row of pillars returned as one line names none of them`() {
        val suggestion = PillarTextParser.parse(
            listOf(
                PillarLine("od C13", 0.023),
                PillarLine("B2", 0.020),
                PillarLine("P", 0.025),
                PillarLine("B17 BL B16 BB15 Bh4 3 2 i", 0.123),
                PillarLine("수구", 0.021),
            ),
        )

        assertEquals("B2", suggestion.floorRaw)
        // Five labels on one row are five pillars the same distance away, and the stray `3`
        // and `2` beside them are the same tie. §6a leaves both blank.
        assertNull(suggestion.zone)
        assertNull(suggestion.spot)
    }

    @Test
    fun `98BBE445 - the next pillar down the row is not this pillar's zone`() {
        val suggestion = PillarTextParser.parse(
            listOf(
                PillarLine("기사지", 0.025),
                PillarLine("그ali", 0.073),
                PillarLine("B119", 0.094),
                PillarLine("B1 18", 0.213),
            ),
        )

        assertEquals("B1", suggestion.floorRaw)
        assertEquals("18", suggestion.spot)
        // The car is at B118. `B119` is the next pillar, painted at less than half the size
        // of the row the bay came from.
        assertNull(suggestion.zone)
    }

    @Test
    fun `94695E1F - a P on a distant wall sign is not the zone`() {
        val suggestion = PillarTextParser.parse(
            listOf(
                PillarLine("428", 0.031),
                PillarLine("B4F", 0.016),
                PillarLine("428n", 0.014),
                PillarLine("P", 0.013),
            ),
        )

        assertEquals("B4F", suggestion.floorRaw)
        assertEquals("428", suggestion.spot)
        assertNull(suggestion.zone)
    }

    @Test
    fun `873CE149 - the photo Vision read nothing in gives up its bay here`() {
        // The wall says B2 and 79; only the 79 was recognised, and a bay alone is still
        // worth offering.
        val suggestion = PillarTextParser.parse(listOf(PillarLine("79", 0.106)))

        assertNull(suggestion.floorRaw)
        assertEquals("79", suggestion.spot)
    }

    @Test
    fun `7B4E5633 - read cleanly, the B1 wall needs no correction at all`() {
        val suggestion = PillarTextParser.parse(listOf(PillarLine("B1", 0.148)))

        assertEquals(PillarSuggestion(floorRaw = "B1"), suggestion)
    }
}
