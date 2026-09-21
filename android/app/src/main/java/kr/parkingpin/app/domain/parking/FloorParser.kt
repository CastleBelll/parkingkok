package kr.parkingpin.app.domain.parking

/**
 * Reads the floor field (docs/01_PRODUCT_REQUIREMENTS.md FR-005) and steps it.
 *
 * FR-005 lists three shapes — `B1..Bn`, `1F..nF`, and free-text fallback. The Korean
 * spellings (`지하 3층`, `3층`) are accepted too: the field is typed by a Korean user
 * standing in a Korean garage, and dropping those into free text would silently cost them
 * the `-`/`+` keys. Anything else is kept verbatim as [FloorKind.FREE_TEXT] rather than
 * rejected, because a floor the parser cannot read is still the floor the car is on.
 */
object FloorParser {

    /**
     * Floors outside this range are treated as free text. The tallest building in Korea
     * has 123 floors and the deepest garages reach B7, so 99 is far past anything real
     * while still rejecting a mistyped year or a spot number pasted into the wrong field.
     */
    const val MAX_LEVEL: Int = 99

    private val basementLatin = Regex("""^[Bb]\s*(\d{1,3})$""")
    private val basementKorean = Regex("""^지하\s*(\d{1,3})\s*층?$""")
    private val groundLatin = Regex("""^(\d{1,3})\s*[Ff]$""")
    private val groundKorean = Regex("""^(?:지상\s*)?(\d{1,3})\s*층$""")
    private val bareNumber = Regex("""^(\d{1,3})$""")

    /**
     * Parses [raw], or returns null when there is no floor at all.
     *
     * Blank is "the user did not say", which is different from an unreadable value — the
     * record's `floorRaw` stays null and the home hero falls back to the zone.
     */
    fun parse(raw: String?): Floor? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        levelOf(trimmed, basementLatin)?.let { return Floor(trimmed, FloorKind.BASEMENT, it) }
        levelOf(trimmed, basementKorean)?.let { return Floor(trimmed, FloorKind.BASEMENT, it) }
        levelOf(trimmed, groundLatin)?.let { return Floor(trimmed, FloorKind.GROUND, it) }
        levelOf(trimmed, groundKorean)?.let { return Floor(trimmed, FloorKind.GROUND, it) }
        // A bare number is the ground floor: "3" on a garage sign means 3층, not B3.
        levelOf(trimmed, bareNumber)?.let { return Floor(trimmed, FloorKind.GROUND, it) }

        return Floor(trimmed, FloorKind.FREE_TEXT, number = null)
    }

    /**
     * Rebuilds a floor from the persisted columns (docs/06 §2 `floorRaw`/`floorKind`/
     * `floorNumber`) without re-parsing.
     *
     * Re-parsing on read would silently rewrite history the day the parser changes; a
     * record must keep meaning what it meant when it was written. A row whose kind and
     * number disagree is a corrupt row, and it degrades to free text rather than throwing.
     */
    fun fromStoredFields(raw: String?, kind: String?, number: Int?): Floor? {
        if (raw == null) return null
        val parsedKind = FloorKind.entries.firstOrNull { it.name == kind } ?: FloorKind.FREE_TEXT
        if (parsedKind != FloorKind.FREE_TEXT && number == null) {
            return Floor(raw, FloorKind.FREE_TEXT, number = null)
        }
        return Floor(raw, parsedKind, number.takeIf { parsedKind != FloorKind.FREE_TEXT })
    }

    /** The floor at [signedIndex] on the ladder described by [Floor.signedIndex]. */
    fun fromSignedIndex(signedIndex: Int): Floor? {
        if (signedIndex == 0 || signedIndex > MAX_LEVEL || signedIndex < -MAX_LEVEL) return null
        return if (signedIndex < 0) {
            val level = -signedIndex
            Floor(raw = "B$level", kind = FloorKind.BASEMENT, number = level)
        } else {
            Floor(raw = "${signedIndex}F", kind = FloorKind.GROUND, number = signedIndex)
        }
    }

    /**
     * [floor] moved by [delta] levels, or null when it cannot move — free text, or a step
     * that would run off either end of the ladder.
     *
     * Returning null rather than clamping keeps the caller honest: the home screen
     * disables the key instead of pretending a press did something.
     */
    fun step(floor: Floor?, delta: Int): Floor? {
        val current = floor?.signedIndex ?: return null
        // Skip the non-existent 0층 when crossing between 지하 and 지상.
        val moved = current + delta
        val target = if (moved == 0) (if (delta > 0) 1 else -1) else moved
        return fromSignedIndex(target)
    }

    private fun levelOf(value: String, pattern: Regex): Int? {
        val level = pattern.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return level.takeIf { it in 1..MAX_LEVEL }
    }
}
