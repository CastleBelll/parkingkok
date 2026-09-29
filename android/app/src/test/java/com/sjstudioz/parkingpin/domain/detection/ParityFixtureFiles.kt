package com.sjstudioz.parkingpin.domain.detection

import com.sjstudioz.parkingpin.domain.location.LocationSample
import com.sjstudioz.parkingpin.domain.trace.LocationQualityBucket
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.PI

/**
 * The `platform-tests` files and their §2 events, shared by every runner that replays them:
 * the engine's parity gate ([ParityFixtureTest]) and the runtime's restore property
 * (`ParkingDetectionRuntimeRestoreTest`). One reading of the files, so the two cannot replay
 * different events from the same bytes.
 *
 * ### Coordinates are reconstructed
 * The fixture schema has no latitude or longitude, by design (contract §8 "좌표 금지"). Fixes
 * are rebuilt on a north-south line, each `distanceFromPreviousM` metres from the one before
 * it — exact for the quantity the engine measures between consecutive fixes.
 */
internal object ParityFixtureFiles {

    const val EPOCH = 1_700_000_000_000L
    const val ORIGIN_LATITUDE = 37.5
    const val ORIGIN_LONGITUDE = 127.0

    /**
     * Metres per degree on the sphere [com.sjstudioz.parkingpin.domain.location.GeoDistance]
     * measures on, so a rebuilt step is exactly the `distanceFromPreviousM` the file recorded —
     * as iOS's runner does against its own sphere. 111 320 m under-read every step by 0.11 %,
     * enough to move a drive across 800 m that iOS replays on the other side.
     */
    const val METERS_PER_DEGREE_LATITUDE = 6_371_008.8 * PI / 180

    private const val FIXTURE_DIRECTORY_NAME = "platform-tests"
    private const val DRAFT_DIRECTORY_NAME = "drafts"
    private const val MILLIS_PER_SECOND = 1_000.0

    val json = Json { ignoreUnknownKeys = true }

    fun fixtureFiles(): List<File> =
        checkNotNull(fixtureDirectory().listFiles { file -> file.extension == "json" }) {
            "platform-tests holds no fixtures"
        }.sortedBy { it.name }

    fun draftFiles(): List<File> =
        checkNotNull(File(fixtureDirectory(), DRAFT_DIRECTORY_NAME).listFiles { file -> file.extension == "json" }) {
            "platform-tests/$DRAFT_DIRECTORY_NAME is missing"
        }.sortedBy { it.name }

    /** Every fixture and every draft, keyed as the golden keys them (`drafts/<name>` for a draft). */
    fun allReplayInputs(): Map<String, ReplayInput> =
        (fixtureFiles().map { it.name to it } + draftFiles().map { "$DRAFT_DIRECTORY_NAME/${it.name}" to it })
            .associate { (key, file) -> key to json.decodeFromString<ReplayInput>(file.readText()) }

    /**
     * Walks up from the module rather than hard-coding a depth: Gradle's working directory for
     * a test task is not something a contract should depend on.
     */
    fun fixtureDirectory(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, FIXTURE_DIRECTORY_NAME)
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile
        }
        error("no '$FIXTURE_DIRECTORY_NAME' directory above ${File("").absolutePath}")
    }

    /**
     * [events] as the engine takes them: relative seconds become absolute milliseconds on
     * [EPOCH] (the engine reads time only from its events, so the origin cannot change the
     * outcome), and each fix lands on the reconstructed line.
     */
    fun detectionEvents(events: List<FixtureEvent>): List<DetectionEvent> {
        var northMeters = 0.0
        return events.map { event ->
            val atMillis = EPOCH + (event.t * MILLIS_PER_SECOND).toLong()
            if (event.type == "location") northMeters += event.distanceFromPreviousM ?: 0.0
            event.toDetectionEvent(atMillis, northMeters)
        }
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
        "projection_connected", "bluetooth_car_connected" ->
            DetectionEvent.CarLinkConnected(atMillis)

        "projection_disconnected", "bluetooth_car_disconnected" ->
            DetectionEvent.CarLinkDisconnected(atMillis)

        "timer_tick" -> DetectionEvent.TimerTick(atMillis)
        "user_confirmed" -> DetectionEvent.UserConfirmedParking(atMillis)
        "user_rejected" -> DetectionEvent.UserRejectedParking(atMillis)
        "user_saved" -> DetectionEvent.UserSavedParking(atMillis)
        "user_kept_parking" -> DetectionEvent.UserKeptParking(atMillis)
        else -> error("unknown fixture event type '$type' — the §2 vocabulary is the contract")
    }
}

/** One §8 fixture event. */
@Serializable
internal data class FixtureEvent(
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
internal data class ReplayInput(
    val name: String,
    val initialState: String,
    val events: List<FixtureEvent>,
)
