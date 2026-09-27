package com.sjstudioz.parkingpin.domain.detection

import com.sjstudioz.parkingpin.analytics.DetectionProperties
import com.sjstudioz.parkingpin.domain.location.LocationSample
import com.sjstudioz.parkingpin.domain.parking.ConfidenceBucket
import com.sjstudioz.parkingpin.domain.trace.LocationQualityBucket
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI

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

    @OptIn(ExperimentalSerializationApi::class)
    private val goldenJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = false
    }

    @Test
    fun `every committed fixture is replayed`() {
        val files = fixtureFiles()

        assertEquals(
            "the whole committed suite must run — a renamed fixture must fail loudly, not vanish",
            setOf(
                "bus_repeated_stops_no_storm.json",
                // Real iPhone drives the user confirmed ended in a parking (promoted 2026-09-27).
                "field_s03_parked.json",
                "field_s04_parked.json",
                "field_s16_parked.json",
                "field_s17_parked.json",
                "field_s26_parked.json",
                "field_s32_parked.json",
                "field_s33_parked.json",
                "long_stop_in_traffic.json",
                "manual_save_parks.json",
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
            replay(fixture).state.failureAgainst(fixture)?.let { "${file.name}: $it" }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * §12 / §3a: a bus that stops four times must not notify four times. The fixture's
     * `expected` fixes the final state; this says the thing the user would actually feel.
     *
     * Counted **per travel session**, as contract §8 and docs/05 §3a "A recording can hold two
     * travel sessions" define it — iOS's `noSessionProducesACandidateStorm` counts the same way
     * ([candidatesPerTravelSession]). Counting per fixture gave the two gates opposite
     * verdicts on identical engine output: field draft s03 holds two journeys with one
     * candidate each, and passed on iOS while failing here.
     */
    @Test
    fun `no travel session produces more than one candidate`() {
        val storms = fixtureFiles().mapNotNull { file ->
            val fixture = json.decodeFromString<Fixture>(file.readText())
            val perSession = candidatesPerTravelSession(replay(fixture).effects)
            "${file.name} produced $perSession candidates per travel session".takeIf { perSession.any { it > 1 } }
        }

        assertTrue(storms.joinToString("\n"), storms.isEmpty())
    }

    @Test
    fun `the storm counter keeps a superseded candidate in its own travel session`() {
        // Arrange — two drives, each ended by an exit and a walk; the second candidate
        // supersedes the first. The user was asked twice, once per trip. iOS twin:
        // `supersededCandidateStillCounts`.
        val effects = replayEffects(
            DetectionEvent.VehicleEnter(at(0)), DetectionEvent.TimerTick(at(150)),
            DetectionEvent.VehicleExit(at(160)), DetectionEvent.WalkingEnter(at(170)),
            DetectionEvent.VehicleEnter(at(200)), DetectionEvent.TimerTick(at(350)),
            DetectionEvent.VehicleExit(at(360)), DetectionEvent.WalkingEnter(at(370)),
        )

        // Act
        val perSession = candidatesPerTravelSession(effects)

        // Assert
        val creates = effects.filterIsInstance<DetectionEffect.CreateCandidate>()
        assertEquals(2, creates.size)
        assertEquals(
            "contract §8: the supersession is withdraw-then-create, adjacent",
            creates[1],
            effects[effects.indexOf(DetectionEffect.RetireCandidate(creates[0].candidateId)) + 1],
        )
        assertEquals(listOf(1, 1), perSession)
    }

    /**
     * docs/05 §17 `long_stop_in_traffic`, replayed by the loop above like every fixture, and
     * kept as its own test for what the loop cannot say: the candidate was silent (§9 `low`
     * posts nothing), the session withdrew it, and contract §8's per-session count is `[0]`.
     * iOS twin: `longStopInTrafficResumesTheDrive`.
     */
    @Test
    fun `long_stop_in_traffic - a jam that moves on retires its silent candidate`() {
        // Arrange
        val fixture = fixtureNamed("long_stop_in_traffic.json")

        // Act
        val outcome = replay(fixture)

        // Assert — one silent candidate, withdrawn, and the drive carries on.
        assertEquals(DetectionState.valueOf(fixture.expected.finalState), outcome.state.state)
        val created = outcome.effects.filterIsInstance<DetectionEffect.CreateCandidate>().single()
        assertEquals(ConfidenceBucket.LOW, created.confidence)
        assertTrue(DetectionEffect.RetireCandidate(created.candidateId) in outcome.effects)
        assertFalse("§9: low posts nothing", candidateAsStored(created).isNotifiable)
        assertEquals(listOf(0), candidatesPerTravelSession(outcome.effects))
    }

    /**
     * GAP2 / GAP7: every committed fixture **and every draft** replays to the outcome trace
     * recorded in `platform-tests/[GOLDEN_TRACE_PATH]`. `expected` is ignored here — a draft has none, or one
     * no conformant engine can meet — so what is pinned is the engine's behaviour, event by
     * event: each event that changed the state, created, withdrew or ended a parking, with the
     * candidate's bucket and full reason set.
     *
     * The iOS runner must assert the same file (see [OutcomeTraceEntry] for the labels): two
     * engines agreeing with one golden agree with each other at every event, and a draft can
     * no longer drift on one platform unseen. Regenerate after a deliberate, spec-backed
     * behaviour change with `UPDATE_PARITY_GOLDEN=1`, and review the diff as a product change.
     */
    @Test
    fun `every fixture and draft replays to its golden outcome trace`() {
        // Arrange
        val actual = (fixtureFiles().map { it.name to it } + draftFiles().map { "drafts/${it.name}" to it })
            .associate { (key, file) ->
                val input = json.decodeFromString<ReplayInput>(file.readText())
                key to replay(input.name, DetectionState.valueOf(input.initialState), input.events).trace
            }
        val goldenFile = goldenTraceFile()
        if (System.getenv(UPDATE_GOLDEN_ENV) == "1") {
            goldenFile.absoluteFile.parentFile?.mkdirs()
            goldenFile.writeText(goldenJson.encodeToString(OutcomeTraceGolden(actual.toSortedMap())) + "\n")
        }

        // Act
        val golden = goldenJson.decodeFromString<OutcomeTraceGolden>(goldenFile.readText()).fixtures

        // Assert
        assertEquals("the golden covers exactly the fixtures and drafts on disk", actual.keys, golden.keys)
        val diverged = actual.mapNotNull { (key, trace) ->
            val expected = golden.getValue(key)
            if (trace == expected) return@mapNotNull null
            val at = trace.indices.firstOrNull { trace[it] != expected.getOrNull(it) } ?: trace.size
            "$key: first difference at trace entry $at — golden ${expected.getOrNull(at)}, replay ${trace.getOrNull(at)}"
        }
        assertTrue(diverged.joinToString("\n"), diverged.isEmpty())
    }

    @Test
    fun `the storm counter opens a travel session on a confirmed departure`() {
        // Arrange — a parking confirmed, then a drive away that §11 confirms as a departure:
        // that drive is a new travel session with its own allowance.
        val effects = replayEffects(
            DetectionEvent.VehicleEnter(at(0)), DetectionEvent.TimerTick(at(150)),
            DetectionEvent.VehicleExit(at(160)), DetectionEvent.WalkingEnter(at(170)),
            DetectionEvent.UserConfirmedParking(at(180)),
            DetectionEvent.VehicleEnter(at(1_000)),
            DetectionEvent.Location(sampleAt(at(1_030), northMeters = 0.0, speedMps = 12f)),
            DetectionEvent.Location(sampleAt(at(1_100), northMeters = 900.0, speedMps = 12f)),
            DetectionEvent.Location(sampleAt(at(1_130), northMeters = 1_300.0, speedMps = 12f)),
        )

        // Act
        val perSession = candidatesPerTravelSession(effects)

        // Assert
        assertTrue(effects.any { it is DetectionEffect.EndActiveParking })
        assertEquals(listOf(1, 0), perSession)
    }

    // ── replay ──────────────────────────────────────────────────────────────────────

    /**
     * The final state, every effect the engine emitted in order, and the [OutcomeTraceEntry]s
     * of the events that changed the product outcome.
     */
    private data class Replay(
        val state: DetectionEngineState,
        val effects: List<DetectionEffect>,
        val trace: List<OutcomeTraceEntry>,
    )

    private fun replay(fixture: Fixture): Replay = replay(fixture.name, fixture.initialState(), fixture.events)

    private fun replay(name: String, initialState: DetectionState, events: List<FixtureEvent>): Replay {
        var ids = 0
        val engine = ParkingDetectionEngine { "$name-candidate-${ids++}" }
        // Relative seconds become absolute milliseconds on an arbitrary epoch. The engine
        // reads time only from the events, so the origin cannot change the outcome.
        var state = DetectionEngineState.startingIn(initialState, EPOCH)
        val effects = mutableListOf<DetectionEffect>()
        val trace = mutableListOf<OutcomeTraceEntry>()
        var northMeters = 0.0

        events.forEachIndexed { index, event ->
            val atMillis = EPOCH + (event.t * MILLIS_PER_SECOND).toLong()
            if (event.type == "location") northMeters += event.distanceFromPreviousM ?: 0.0
            val step = engine.handle(state, event.toDetectionEvent(atMillis, northMeters))
            val labels = step.effects.mapNotNull(::outcomeLabel)
            val stateChanged = step.state.state != state.state
            if (stateChanged || labels.isNotEmpty()) {
                trace += OutcomeTraceEntry(
                    event = index,
                    t = event.t,
                    state = step.state.state.name.takeIf { stateChanged },
                    effects = labels,
                )
            }
            state = step.state
            effects += step.effects
        }
        return Replay(state, effects, trace)
    }

    /** Hand-written events from `IDLE`, for the counter's own tests. */
    private fun replayEffects(vararg events: DetectionEvent): List<DetectionEffect> {
        var ids = 0
        val engine = ParkingDetectionEngine { "counter-candidate-${ids++}" }
        var state = DetectionEngineState.startingIn(DetectionState.IDLE, EPOCH)
        return events.flatMap { event -> engine.handle(state, event).also { state = it.state }.effects }
    }

    private fun fixtureNamed(name: String): Fixture =
        json.decodeFromString(File(fixtureDirectory(), name).readText())

    private fun at(seconds: Long): Long = EPOCH + seconds * 1_000L

    /** The candidate as the runtime stores it, which is what decides whether it is posted. */
    private fun candidateAsStored(created: DetectionEffect.CreateCandidate) = ParkingCandidate.of(
        id = created.candidateId,
        detectedAtMillis = EPOCH,
        lastReliableLocation = created.lastReliableLocation,
        evidence = DetectionProperties(
            confidenceBucket = created.confidence,
            walkingEvidence = created.walkingEvidence,
            gpsDegradation = created.gpsDegradation,
            optionalVehicleSignal = created.optionalVehicleSignal,
        ),
    )

    private fun sampleAt(atMillis: Long, northMeters: Double, speedMps: Float?) = LocationSample(
        atMillis = atMillis,
        latitude = ORIGIN_LATITUDE + northMeters / METERS_PER_DEGREE_LATITUDE,
        longitude = ORIGIN_LONGITUDE,
        horizontalAccuracyM = 8f,
        speedMps = speedMps,
    )

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

    private fun FixtureEvent.toDetectionEvent(atMillis: Long, northMeters: Double): DetectionEvent = when (type) {
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

        // The buckets are the event's meaning: §8b credits only a fall into `poor`, so a
        // good→fair drop replayed without them would read as a drop to `poor` and earn a
        // weight iOS — whose runner passes them — does not give (field draft s17).
        "location_quality_degraded" -> DetectionEvent.LocationQualityDegraded(
            atMillis = atMillis,
            fromBucket = fromBucket,
            toBucket = toBucket,
        )
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
        "user_saved" -> DetectionEvent.UserSavedParking(atMillis)
        else -> error("unknown fixture event type '$type' — the §2 vocabulary is the contract")
    }


    // ── the files ───────────────────────────────────────────────────────────────────

    private fun fixtureFiles(): List<File> =
        checkNotNull(fixtureDirectory().listFiles { file -> file.extension == "json" }) {
            "platform-tests holds no fixtures"
        }.sortedBy { it.name }

    private fun draftFiles(): List<File> =
        checkNotNull(File(fixtureDirectory(), DRAFT_DIRECTORY_NAME).listFiles { file -> file.extension == "json" }) {
            "platform-tests/$DRAFT_DIRECTORY_NAME is missing"
        }.sortedBy { it.name }

    /**
     * The one shared golden under `platform-tests/`, the same file the iOS runner asserts.
     * Android both reads it and, with `UPDATE_PARITY_GOLDEN=1`, rewrites it in place — a
     * private copy would let one platform regenerate while the other asserts a stale file.
     */
    private fun goldenTraceFile(): File = File(fixtureDirectory(), GOLDEN_TRACE_PATH).also {
        check(it.isFile || System.getenv(UPDATE_GOLDEN_ENV) == "1") { "no golden at ${it.absolutePath}" }
    }

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
        val fromBucket: LocationQualityBucket? = null,
        val toBucket: LocationQualityBucket? = null,
    )

    /** A fixture or a draft, read for its events alone: a draft may have no `expected`. */
    @Serializable
    private data class ReplayInput(
        val name: String,
        val initialState: String,
        val events: List<FixtureEvent>,
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
        const val DRAFT_DIRECTORY_NAME = "drafts"
        const val GOLDEN_TRACE_PATH = "goldens/outcome-traces.golden.json"
        const val UPDATE_GOLDEN_ENV = "UPDATE_PARITY_GOLDEN"
        const val EPOCH = 1_700_000_000_000L
        const val MILLIS_PER_SECOND = 1_000.0

        const val ORIGIN_LATITUDE = 37.5
        const val ORIGIN_LONGITUDE = 127.0
        /**
         * Metres per degree on the sphere [com.sjstudioz.parkingpin.domain.location.GeoDistance]
         * measures on, so a rebuilt step is exactly the `distanceFromPreviousM` the file
         * recorded — as iOS's runner does against its own sphere. The 111 320 m this used to
         * be under-read every step by 0.11 %, enough to move a leg across the noise floor
         * or a drive across 800 m that iOS replays on the other side.
         */
        const val METERS_PER_DEGREE_LATITUDE = 6_371_008.8 * PI / 180
    }
}

/**
 * Contract §8's storm counter: how many candidates each travel session produced, in order.
 *
 * The port of iOS `ParityFixtureOutcome.candidatesPerTravelSession`, rule for rule, so the two
 * parity gates judge the same engine output the same way:
 *
 * - a travel session opens when a checkpoint enters `DRIVING_CANDIDATE` from another state,
 *   or enters `DRIVING` from `DEPARTURE_CANDIDATE` (a confirmed departure, §11) — the places
 *   the engine itself starts a trip. An empty session is reused rather than a new one opened;
 * - a [DetectionEffect.CreateCandidate] counts for the session it was created in;
 * - a [DetectionEffect.RetireCandidate] not immediately followed by a create is the session
 *   taking its candidate back (a link reconnect, a stop-only resume, an expiry) and no longer
 *   counts. One followed by a create is a supersession, and both stay counted.
 *
 * Both engines emit a supersession as `RetireCandidate(old)` immediately followed by
 * `CreateCandidate(new)` (contract §8; pinned on Android by `ParkingDetectionEngineTest` "a
 * candidate superseded by the next journey is retired directly before the new one is created").
 */
internal fun candidatesPerTravelSession(effects: List<DetectionEffect>): List<Int> {
    val counts = mutableListOf(0)
    val sessionOfCandidate = mutableMapOf<String, Int>()
    var previousState: DetectionState? = null
    effects.forEachIndexed { index, effect ->
        when (effect) {
            is DetectionEffect.PersistCheckpoint -> {
                val state = effect.checkpoint.state
                val opensSession =
                    (state == DetectionState.DRIVING_CANDIDATE && previousState != DetectionState.DRIVING_CANDIDATE) ||
                        (state == DetectionState.DRIVING && previousState == DetectionState.DEPARTURE_CANDIDATE)
                if (opensSession && counts.last() != 0) counts += 0
                previousState = state
            }

            is DetectionEffect.CreateCandidate -> {
                sessionOfCandidate[effect.candidateId] = counts.lastIndex
                counts[counts.lastIndex] += 1
            }

            is DetectionEffect.RetireCandidate -> {
                val supersedes = effects.getOrNull(index + 1) is DetectionEffect.CreateCandidate
                val session = sessionOfCandidate[effect.candidateId]
                if (!supersedes && session != null) counts[session] -= 1
            }

            is DetectionEffect.MarkParkingActive,
            is DetectionEffect.EndActiveParking,
            -> Unit
        }
    }
    return counts
}

/**
 * One event of a replay that changed the product outcome — the unit of the golden outcome
 * trace both runners assert (`every fixture and draft replays to its golden outcome trace`).
 *
 * Platform-neutral on purpose, so the Swift runner can produce the identical file:
 * - [event] is the index into the fixture's `events`, [t] its relative time;
 * - [state] is the engine state after the event, present only when the event changed it;
 * - [effects] are, in emission order, `withdraw`, `create <bucket> <sorted wire codes>` and
 *   `endActiveParking` — the §15 effects contract §8 compares. Checkpoints, notifications and
 *   capture requests are not outcomes and are not recorded.
 */
@Serializable
internal data class OutcomeTraceEntry(
    val event: Int,
    val t: Double,
    val state: String? = null,
    val effects: List<String> = emptyList(),
)

@Serializable
internal data class OutcomeTraceGolden(val fixtures: Map<String, List<OutcomeTraceEntry>>)

/** The [OutcomeTraceEntry] label of [effect], or null for an effect that is not an outcome. */
internal fun outcomeLabel(effect: DetectionEffect): String? = when (effect) {
    is DetectionEffect.CreateCandidate ->
        "create ${effect.confidence.name.lowercase()} ${effect.reasons.map { it.wire }.sorted().joinToString(",")}"
    is DetectionEffect.RetireCandidate -> "withdraw"
    is DetectionEffect.EndActiveParking -> "endActiveParking"
    is DetectionEffect.MarkParkingActive,
    is DetectionEffect.PersistCheckpoint,
    -> null
}
