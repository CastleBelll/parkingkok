package com.sjstudioz.parkingpin.ui.components

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.sjstudioz.parkingpin.domain.parking.ParkingLocation

/**
 * A coordinate on its way to the map, and nowhere else.
 *
 * [toString] is redacted like [ParkingLocation]'s, so a UI state that carries one cannot
 * leak it through an accidental log line (docs/00_CORE_RULES.md Privacy).
 */
data class MapPoint(val latitude: Double, val longitude: Double) {
    override fun toString(): String = "MapPoint(redacted)"
}

fun ParkingLocation.toMapPoint(): MapPoint = MapPoint(latitude, longitude)

/**
 * The parked location on a real map — or, when there is no map to draw, the block plan.
 *
 * docs/04_ANDROID_IMPLEMENTATION.md §12 was revised on 2026-09-24: the drawn plan looked
 * like a map and described no place at all, which is worse than either a map or nothing.
 * This is Maps SDK **lite mode** — one rendered bitmap, no gestures, no live GL surface —
 * because every place it appears is a thumbnail or a card, and the real map is still the
 * external app `주차 위치 보기` opens.
 *
 * The cost, accepted with the decision: drawing it sends the area around [point] to Google
 * to fetch the tiles. It is disclosed in docs/09 and in the Play Data Safety form.
 *
 * Falls back to [StaticLocationArtwork] when there is no [point], when the build carries no
 * Maps key (CI, forks — see `PK_MAPS_API_KEY`), and in previews, where there is no SDK.
 */
@Composable
fun ParkingLocationMap(
    point: MapPoint?,
    modifier: Modifier = Modifier,
    pinLabel: String? = null,
    zoneLabel: String? = null,
    pinSize: Dp = 28.dp,
) {
    val context = LocalContext.current
    val configured = remember(context) { MapsKey.isConfigured(context) }
    if (point == null || !configured || LocalInspectionMode.current) {
        StaticLocationArtwork(modifier, pinLabel, zoneLabel, pinSize)
        return
    }

    val target = LatLng(point.latitude, point.longitude)
    // Keyed on the point so a different record moves the camera instead of keeping the old one.
    val camera = remember(point) {
        CameraPositionState(position = CameraPosition.fromLatLngZoom(target, MAP_ZOOM))
    }
    val shape = MaterialTheme.shapes.large
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        GoogleMap(
            // The caption beside the map is what a screen reader should hear.
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { },
            googleMapOptionsFactory = { GoogleMapOptions().liteMode(true).mapToolbarEnabled(false) },
            cameraPositionState = camera,
            uiSettings = LITE_UI_SETTINGS,
            // Lite mode opens Google Maps itself on a tap unless a listener takes it. The
            // app's own `주차 위치 보기` is the one way out to a map, so this swallows it.
            onMapClick = {},
        )
        MapPinOverlay(pinLabel, zoneLabel, pinSize, tipAtCenter = true)
    }
}

/** Reads the key the build injected, once per composition of a map. */
internal object MapsKey {
    private const val META_DATA_NAME = "com.google.android.geo.API_KEY"

    fun isConfigured(context: Context): Boolean {
        val value = try {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.getString(META_DATA_NAME)
        } catch (missing: PackageManager.NameNotFoundException) {
            null
        }
        return isUsable(value)
    }

    /**
     * An empty value is a build with no key; an unexpanded `${...}` is a manifest that was
     * never given the placeholder. Either way the SDK would draw a grey box, so neither is
     * a key.
     */
    fun isUsable(value: String?): Boolean =
        !value.isNullOrBlank() && !value.startsWith("\${")
}

/**
 * Street level: a car park and the block it sits on. Closer shows nothing but one
 * building's roof; further stops answering "which entrance".
 */
private const val MAP_ZOOM = 17f

private val LITE_UI_SETTINGS = MapUiSettings(
    compassEnabled = false,
    mapToolbarEnabled = false,
    myLocationButtonEnabled = false,
    zoomControlsEnabled = false,
    scrollGesturesEnabled = false,
    zoomGesturesEnabled = false,
    tiltGesturesEnabled = false,
    rotationGesturesEnabled = false,
)
