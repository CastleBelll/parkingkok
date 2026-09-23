package com.sjstudioz.parkingpin.domain.photo

import com.sjstudioz.parkingpin.domain.parking.Floor
import com.sjstudioz.parkingpin.domain.parking.FloorKind
import com.sjstudioz.parkingpin.domain.parking.FloorParser
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a photo of a pillar suggested — never what was saved
 * (docs/02_PRODUCT_SCOPE_AND_FLOWS.md §6a "What it produces").
 *
 * Every field is nullable and [NONE] is the ordinary answer: "most pillars are not
 * photographed straight, in good light, from two metres", and a partial read fills what
 * parsed and leaves the rest blank.
 */
data class PillarSuggestion(
    /** Raw text, as read. [FloorParser] has already accepted it. */
    val floorRaw: String? = null,
    val zone: String? = null,
    val spot: String? = null,
) {
    val isEmpty: Boolean get() = floorRaw == null && zone == null && spot == null

    companion object {
        /** Nothing was read, or nothing parsed. Indistinguishable on purpose (§6a). */
        val NONE = PillarSuggestion()
    }
}

/**
 * Recognised text on demand. Implemented by the on-device recogniser.
 *
 * **It never throws and it never reports a failure.** §6a: no text found, an unreadable
 * image and a missing model are the same outcome — an empty list — because the user is
 * shown the same empty form in all three cases and "a feature that apologises every time
 * it cannot read a wall is worse than one that quietly helps when it can".
 */
/**
 * One painted row, and how tall it was drawn.
 *
 * The height is the only thing in a photo that says which pillar is nearest, and the
 * nearest is the one the car is at. A fraction of the image height, so it is comparable
 * within one photo and meaningless across two; zero means the reader did not measure.
 */
data class PillarLine(val text: String, val height: Double = 0.0)

fun interface PillarTextReader {

    /** The lines recognised in [source], or empty. */
    suspend fun read(source: PhotoSource): List<PillarLine>

    /**
     * Load whatever the first read would otherwise load, somewhere the user is not
     * waiting. Called when the photo UI opens.
     *
     * Default no-op: a fake in a test has nothing to warm, and a reader that needs no
     * warming should not have to say so.
     */
    suspend fun prepare() {}
}

/**
 * Turns the lines a recogniser found into the fields the form offers (§6a).
 *
 * ### It does not parse floors
 * [FloorParser] does, and this only decides *which* piece of text to hand it. docs/02 §6
 * already fixes what a floor looks like — `B3`, `지하 3층`, `3F` — and a second parser
 * here would be a second answer to the same question.
 *
 * ### One reading rule the parser does not have
 * A bare number is **not** a floor on a pillar. [FloorParser] reads `3` as 3층, which is
 * right for a field the user typed and wrong for a wall covered in bay numbers: `142`
 * would become the 142nd floor, and a `B3` misread as `83` would become the 83rd. Here a
 * floor has to carry its marker — `B`, `지하`, `F` or `층` — and a bare number is read as
 * the bay it almost always is.
 */
object PillarTextParser {

    fun parse(lines: List<String>): PillarSuggestion =
        parse(lines.map { PillarLine(it) })

    @JvmName("parseLines")
    fun parse(observed: List<PillarLine>): PillarSuggestion {
        val lines = observed.map(PillarLine::text)
        // Tallest wins per token: the same label can appear twice, and what matters is the
        // biggest it was painted.
        val heights = observed
            .groupBy(PillarLine::text)
            .mapValues { (_, seen) -> seen.maxOf(PillarLine::height) }
        return parse(lines, heights)
    }

    private fun parse(lines: List<String>, heights: Map<String, Double>): PillarSuggestion {
        val windows = lines.flatMap(::windowsOf)
        // Per line, because **the first floor-shaped window decides that line even when it
        // decides against one**. Falling through to a narrower window re-reads a fragment
        // of the same sign: `지하 15층` is rejected as a floor nobody has, and its second
        // word alone is `15층` — a basement turned into a storey, thirty floors away.
        val floor = lines.firstNotNullOfOrNull(::floorInLine) ?: repeatedBadge(lines)
        // What the floor took: the text it chose, and — when that text was corrected from a
        // misread badge — the digits it came from. Neither may come back as the bay or as
        // the pillar's own number.
        val used = buildSet {
            floor?.let(::add)
            if (floor != null && lines.none { it == floor }) add("8" + floor.drop(1))
        }
        val words = lines.flatMap { it.split(WHITESPACE) }.filter(String::isNotEmpty)
        return PillarSuggestion(
            floorRaw = floor,
            zone = windows.firstNotNullOfOrNull(::zoneOrNull) ?: pillarLabel(words, used, heights),
            spot = windows.filterNot(used::contains).firstNotNullOfOrNull(::spotOrNull),
        )
    }

    /**
     * The one- and two-word phrases of [line], longer first at each position.
     *
     * Two words because `지하 3층` and `A 구역` are each one value written with a space in
     * it, and splitting them on whitespace turns the first into `3층` — the third floor
     * above ground, three levels away from where the car is.
     */
    private fun windowsOf(line: String): List<String> {
        val words = line.split(WHITESPACE).filter { it.isNotEmpty() }
        return words.indices.flatMap { index ->
            listOfNotNull(
                words.getOrNull(index + 1)?.let { "${words[index]} $it" },
                words[index],
            )
        }
    }

    /**
     * The floor badge every pillar carries, when the recogniser turned its `B` into an `8`.
     *
     * Measured on a phone photographing a B2 garage numbered B14-B17:
     *
     * ```text
     * "10/ C13", "a", "82", "B17", "B", "82", "B16", "[", "82", "B B15", "814", "82"
     * ```
     *
     * `B2` was never read — it came back as `82`, four times. `B`→`8` is the ordinary OCR
     * confusion and on its own is not enough to act on: a bare number on a pillar is the
     * bay, and rewriting every `82` into `B2` would invent a floor out of a bay number.
     *
     * **Repetition is what makes it safe.** The badge is identical on every pillar in frame
     * while bay and pillar numbers all differ, so a digit run is re-read as a floor only
     * when it appears more than once *and* the corrected value is a floor a garage has. A
     * close-up of one pillar has no repetition and needs none: at that distance the badge
     * reads as `B2` and the ordinary path takes it.
     */
    private fun repeatedBadge(lines: List<String>): String? =
        lines.flatMap { it.split(WHITESPACE) }
            .filter { it.length >= 2 && it.startsWith("8") && it.all(Char::isDigit) }
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= 2 }
            // Most repeated first, then the shallower floor: `82` before `83` is a coin
            // toss worth deciding the same way every time.
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { "B" + it.key.drop(1) }
            .firstOrNull { text ->
                FloorParser.parse(text)?.let(::isPlausibleFloor) == true
            }

    private fun floorInLine(line: String): String? {
        val stated = windowsOf(line).firstNotNullOfOrNull { window ->
            floorOrNull(window)?.let { window to it }
        } ?: return null
        val (window, floor) = stated
        return window.takeIf { isPlausibleFloor(floor) }
    }

    private fun floorOrNull(candidate: String): Floor? {
        if (BARE_NUMBER.matches(candidate)) return null
        val floor = FloorParser.parse(candidate) ?: return null
        // Free text is "the parser could not read this", which on a wall of signage is
        // every other word. Only a floor it recognised is worth suggesting.
        return floor.takeIf { it.kind != FloorKind.FREE_TEXT }
    }

    /**
     * Whether a parsed floor is one a garage has.
     *
     * A pillar carries two `B`-numbers — `B2` for the floor and `B17` for the pillar — and
     * on the wall the only thing that tells them apart is how big the number is. Measured
     * on a real photo of a B2 garage numbered B14–B17: the recogniser returned `B17` with
     * confidence 1.00 and `B2` with 0.30, and iOS offered 지하 17층 for a car parked on B2.
     * Neither order nor confidence separates them; magnitude does.
     *
     * Korean garages bottom out around B7 and a handful reach B10. Past that a number is a
     * bay or a pillar id, and offering it is worse than offering nothing — the user has to
     * notice and undo it. A genuinely deeper garage gets no suggestion, which is exactly
     * what a failed read already does. Field-tuning starting points, mirrored on iOS in
     * `PillarFloorSuggestion`.
     */
    private fun isPlausibleFloor(floor: Floor): Boolean {
        val number = floor.number ?: return false
        return when (floor.kind) {
            FloorKind.BASEMENT -> number <= DEEPEST_BASEMENT
            FloorKind.GROUND -> number <= HIGHEST_STOREY
            FloorKind.FREE_TEXT -> false
        }
    }

    /**
     * `B17`, `C13`, `가12` — the number painted on the pillar itself.
     *
     * Not a zone in the `A구역` sense, and it is what the user would write down anyway: in a
     * garage whose pillars are labelled, "B17" *is* where the car is. Offered only when the
     * photo settles which pillar is meant:
     *
     * * **The floor badge is excluded.** It repeats on every pillar in frame while pillar
     *   numbers all differ, so anything appearing more than once is the badge — as are the
     *   digits the floor correction already consumed.
     * * **Several distinct labels means none.** A wide shot catching B14 through B17 cannot
     *   say which one the car is at, and a confident wrong pillar sends the user to the
     *   wrong end of the floor. Photographing the pillar in front of them leaves one.
     */
    private fun pillarLabel(words: List<String>, used: Set<String>, heights: Map<String, Double>): String? {
        val labels = words
            .filter { PILLAR_LABEL.matches(it) }
            .groupingBy { it }
            .eachCount()
            .filterValues { it == 1 }
            .keys
            .filterNot(used::contains)
        labels.singleOrNull()?.let { return it }

        // Several pillars in frame: the one the car is at is the one nearest the camera,
        // and the nearest is painted largest. Ties are left alone — two pillars the same
        // size are two pillars the photo cannot choose between, and a reader that measured
        // nothing reports zero, which ties with everything.
        val ranked = labels
            // A height of zero is "not measured", not "flat": it must not rank.
            .mapNotNull { label -> heights[label]?.takeIf { it > 0.0 }?.let { label to it } }
            .sortedByDescending { it.second }
        val nearest = ranked.firstOrNull() ?: return null
        val next = ranked.getOrNull(1) ?: return nearest.first
        return nearest.first.takeIf { nearest.second >= next.second * NEAREST_MARGIN }
    }

    private fun zoneOrNull(candidate: String): String? =
        ZONE.matchEntire(candidate)?.let { candidate.replace(WHITESPACE, "") }

    private fun spotOrNull(candidate: String): String? =
        SPOT.matchEntire(candidate)?.groupValues?.get(1)

    /**
     * How much taller the nearest label has to be before it is believed to be nearest.
     *
     * Measured on the wide shot that raised this: `B17` at 0.061 of the image height
     * against `BB15` at 0.050, a ratio of 1.22. Below this the photo is looking down a row
     * of equally distant pillars and has no answer. Mirrored on iOS.
     */
    const val NEAREST_MARGIN = 1.15

    const val DEEPEST_BASEMENT = 10
    const val HIGHEST_STOREY = 20

    private val WHITESPACE = Regex("""\s+""")

    private val BARE_NUMBER = Regex("""^\d{1,3}$""")

    /** `A구역`, `A 구역`, `가구역`. The label before 구역 is short by nature. */
    private val ZONE = Regex("""^[가-힣A-Za-z0-9]{1,6}\s*구역$""")

    /** One or two letters — Latin or Hangul — then one to three digits, and nothing else. */
    private val PILLAR_LABEL = Regex("""^[가-힣A-Za-z]{1,2}\d{1,3}$""")

    /** `142`, `142번`. Kept as digits, because that is what the field holds. */
    private val SPOT = Regex("""^(\d{1,4})번?$""")
}

/**
 * Reads the pillar in [PhotoSource], or gives up quietly.
 *
 * The timeout is the third of §6a's three silent failures: "the model is unavailable or
 * takes too long → same as no text found".
 *
 * **It has to survive a cold model load.** iOS measured its recogniser at 5224ms on the
 * first call in a process against ~1010ms warm, and a four-second deadline there lost
 * *every* first read — silently, because §6a makes failure quiet, and invisibly, because
 * a warm test suite never sees it. This deadline was 3000ms, which would have lost them
 * too. The pairing matters more than the number: [PillarTextReader.prepare] moves the load
 * off the user's path, and the deadline is generous enough that a read which somehow
 * arrives cold still finishes.
 */
class ReadPillarSuggestionUseCase(
    private val reader: PillarTextReader,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    suspend operator fun invoke(source: PhotoSource): PillarSuggestion =
        withTimeoutOrNull(timeoutMillis) { reader.read(source) }
            ?.let(PillarTextParser::parse)
            ?: PillarSuggestion.NONE

    companion object {
        /**
         * Sized for a cold model load, not a warm read.
         *
         * Carried over from the iOS measurement rather than measured on ML Kit — a
         * bundled model has the same first-use cost in kind, and an instrumented test
         * warms the process before it can time one. Worth measuring properly when someone
         * can; too generous costs nothing here, because [PillarTextReader.prepare] means
         * almost no read ever approaches it.
         */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 6_000L
    }
}
