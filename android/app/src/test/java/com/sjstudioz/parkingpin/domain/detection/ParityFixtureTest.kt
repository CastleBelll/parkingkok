package com.sjstudioz.parkingpin.domain.detection

import com.sjstudioz.parkingpin.domain.location.LocationSample
import com.sjstudioz.parkingpin.domain.parking.ConfidenceBucket
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Replays the JSON fixtures in `platform-tests` through [ParkingDetectionEngine] and
 * checks the result against each fixture's `expected` block.
 *
 * ### Why this test is the point of the whole engine
 * docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §8 says both projects must run an equivalent
 * fixture suite, and docs/05_PARKING_DETECTION_ENGINE.md §17 makes them mandatory. Two
 * engines written from one document from two languages will diverge; nothing else in this
 * repository would notice. These files are the only shared executable statement of what
 * the product does, so **a failure here is the engine being wrong, not the fixture.**
 *
 * ### The files are read, not copied
 * The directory is read straight off disk so the Swift suite and this one can never
 * drift onto two versions of the same contract. [fixtureDirectory] walks up from the
 * module rather than hard-coding a depth, because Gradle's working directory for a test
 * task is not something a contract should depend on.
 *
 * ### Coordinates are reconstructed, and this is the honest part
 * The fixture schema has no latitude or longitude, by design (§8 "좌표 금지"): a file that
 * is copied off the device must not become a record of where someone parks. But §7's
 * movement clause and distance accumulation are statements about a *displacement*, so the
 * engine needs two points. The runner therefore lays each fix out on a straight
 * north-south line, `distanceFromPreviousM` metres from the one before it, with a missing
 * value meaning "no displacement recorded".
 *
 * That reconstruction is exact for the quantity the engine actually measures between
 * consecutive fixes, and an over-estimate across a longer anchor on a path that turns —
 * a straight line is the longest a given set of legs can reach. It is stated here rather
 * than hidden because it is the one place this runner is not simply replaying the file.
 */
class ParityFixtureTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `every committed fixture is replayed`() {
        val files = fixtureFiles()

        assertEquals(
            "the whole committed suite must run — a renamed fixture must fail loudly, not vanish",
            setOf(
                "bus_repeated_stops_no_storm.json",
                "quiet_transition_expires_no_candidate.json",
                "red_light_no_candidate.json",
                "subway_commute_underground.json",
                "tunnel_no_parking.json",
                "vehicle_then_walk.json",
            ),
            files.map { it.name }.toSet(),
        )
    }

    @Test
    fun `fixtures reach the state the contract expects`() {
        val failures = fixtureFiles().mapNotNull { file ->
            val fixture = json.decodeFromString<Fixture>(file.readText())
            replay(fixture).failureAgainst(fixture)?.let { "${file.name}: $it" }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ── replay ──────────────────────────────────────────────────────────────────────

    private fun replay(fixture: Fixture): DetectionEngineState {
        var ids = 0
        val engine = ParkingDetectionEngine { "${fixture.name}-candidate-${ids++}" }
        // Relative seconds become absolute milliseconds on an arbitrary epoch. The engine
        // reads time only from the events, so the origin cannot change the outcome.
        var state = DetectionEngineState.startingIn(fixture.initialState(), EPOCH)
        var northMeters = 0.0

        for (event in fixture.events) {
            val atMillis = EPOCH + (event.t * MILLIS_PER_SECOND).toLong()
            if (event.type == "location") northMeters += event.distanceFromPreviousM ?: 0.0
            val detectionEvent = event.toDetectionEvent(atMillis, northMeters) ?: continue
            state = engine.handle(state, detectionEvent).state
        }
        return state
    }

    private fun DetectionEngineState.failureAgainst(fixture: Fixture): String? {
        val expected = fixture.expected
        val problems = buildList {
            val expectedState = DetectionState.valueOf(expected.finalState)
            if (state != expectedState) add("finalState expected $expectedState but was $state")
            val produced = candidatesCreated > 0
            if (produced != expected.candidate) {
                add("candidate expected ${expected.candidate} but was $produced (created=$candidatesCreated)")
            }
            expected.confidence?.let { wanted ->
                val actual = candidate?.confidence
                if (actual != ConfidenceBucket.valueOf(wanted.uppercase())) {
                    add("confidence expected $wanted but was $actual (score=${candidate?.score})")
                }
            }
            // A *required* subset, not an equality: §3a lets codes accumulate freely, and a
            // fixture pins the ones the outcome depends on.
            val actualReasons = candidate?.reasons.orEmpty().map { it.wire }.toSet()
            val missing = expected.requiredReasons.orEmpty().toSet() - actualReasons
            if (missing.isNotEmpty()) add("missing reason codes $missing (had $actualReasons)")
        }
        return problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun FixtureEvent.toDetectionEvent(atMillis: Long, northMeters: Double): DetectionEvent? = when (type) {
        "vehicle_enter" -> DetectionEvent.VehicleEnter(atMillis)
        "vehicle_exit" -> DetectionEvent.VehicleExit(atMillis)
        "walking_enter" -> DetectionEvent.WalkingEnter(atMillis)
        "stationary_enter" -> DetectionEvent.StationaryEnter(atMillis)
        "stationary_exit" -> DetectionEvent.StationaryExit(atMillis)
        "location" -> DetectionEvent.Location(
            LocationSample(
                atMillis = atMillis,
                latitude = ORIGIN_LATITUDE + northMeters / METERS_PER_DEGREE_LATITUDE,
                longitude = ORIGIN_LONGITUDE,
                horizontalAccuracyM = checkNotNull(accuracy) { "a location fixture event needs an accuracy" },
                speedMps = speed,
            ),
        )

        "location_quality_degraded" -> DetectionEvent.LocationQualityDegraded(atMillis)
        // §2's four link spellings and no others. Android's engine does not distinguish
        // projection from Bluetooth — §3a treats them as one signal — but the *names* are
        // the contract, and accepting a fifth here would let a fixture pass on this
        // platform and throw on iOS, which is the one thing a parity gate must not do.
        // This used to also take `car_projection_connected`/`_disconnected` and
        // `user_confirmed_parking`/`user_rejected_parking`; neither is in §2.
        "projection_connected", "bluetooth_car_connected" ->
            DetectionEvent.CarLinkConnected(atMillis)

        "projection_disconnected", "bluetooth_car_disconnected" ->
            DetectionEvent.CarLinkDisconnected(atMillis)

        "timer_tick" -> DetectionEvent.TimerTick(atMillis)
        "user_confirmed" -> DetectionEvent.UserConfirmedParking(atMillis)
        "user_rejected" -> DetectionEvent.UserRejectedParking(atMillis)
        else -> error("unknown fixture event type '$type' — the §2 vocabulary is the contract")
    }


    // ── the files ───────────────────────────────────────────────────────────────────

    private fun fixtureFiles(): List<File> =
        checkNotNull(fixtureDirectory().listFiles { file -> file.extension == "json" }) {
            "platform-tests holds no fixtures"
        }.sortedBy { it.name }

    private fun fixtureDirectory(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, FIXTURE_DIRECTORY_NAME)
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile
        }
        error("no '$FIXTURE_DIRECTORY_NAME' directory above ${File("").absolutePath}")
    }

    // ── §8 fixture schema ───────────────────────────────────────────────────────────

    @Serializable
    private data class Fixture(
        val name: String,
        @SerialName("initialState") val initialStateName: String,
        val events: List<FixtureEvent>,
        val expected: FixtureExpectation,
    ) {
        fun initialState(): DetectionState = DetectionState.valueOf(initialStateName)
    }

    @Serializable
    private data class FixtureEvent(
        val type: String,
        val t: Double,
        val accuracy: Float? = null,
        val speed: Float? = null,
        val distanceFromPreviousM: Double? = null,
        val confidence: String? = null,
        val fromBucket: String? = null,
        val toBucket: String? = null,
    )

    @Serializable
    private data class FixtureExpectation(
        val candidate: Boolean,
        val finalState: String,
        val confidence: String? = null,
        val requiredReasons: List<String>? = null,
    )

    private companion object {
        const val FIXTURE_DIRECTORY_NAME = "platform-tests"
        const val EPOCH = 1_700_000_000_000L
        const val MILLIS_PER_SECOND = 1_000.0

        const val ORIGIN_LATITUDE = 37.5
        const val ORIGIN_LONGITUDE = 127.0
        const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    }
}
