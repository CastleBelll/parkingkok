package com.parkingkok.app.domain.photo

import com.parkingkok.app.domain.parking.Floor
import com.parkingkok.app.domain.parking.FloorKind
import com.parkingkok.app.domain.parking.FloorParser
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
fun interface PillarTextReader {

    /** The lines recognised in [source], or empty. */
    suspend fun read(source: PhotoSource): List<String>

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

    fun parse(lines: List<String>): PillarSuggestion {
        val windows = lines.flatMap(::windowsOf)
        val floor = windows.firstNotNullOfOrNull(::floorOrNull)
        return PillarSuggestion(
            floorRaw = floor,
            zone = windows.firstNotNullOfOrNull(::zoneOrNull),
            spot = windows.firstNotNullOfOrNull(::spotOrNull),
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

    private fun floorOrNull(candidate: String): String? {
        if (BARE_NUMBER.matches(candidate)) return null
        val floor = FloorParser.parse(candidate) ?: return null
        // Free text is "the parser could not read this", which on a wall of signage is
        // every other word. Only a floor it recognised is worth suggesting.
        return candidate.takeIf { floor.kind != FloorKind.FREE_TEXT }
    }

    private fun zoneOrNull(candidate: String): String? =
        ZONE.matchEntire(candidate)?.let { candidate.replace(WHITESPACE, "") }

    private fun spotOrNull(candidate: String): String? =
        SPOT.matchEntire(candidate)?.groupValues?.get(1)

    private val WHITESPACE = Regex("""\s+""")

    private val BARE_NUMBER = Regex("""^\d{1,3}$""")

    /** `A구역`, `A 구역`, `가구역`. The label before 구역 is short by nature. */
    private val ZONE = Regex("""^[가-힣A-Za-z0-9]{1,6}\s*구역$""")

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
