package com.parkingkok.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.parkingkok.app.R
import com.parkingkok.app.theme.spacing

/**
 * The map block of `01-home-main.png` and `03-parking-detail.png`, drawn rather than fetched.
 *
 * ## Why this is not a map
 *
 * Android's FR-008 is an external maps intent (docs/04_ANDROID_IMPLEMENTATION.md §12,
 * 2026-09-18): no Maps SDK, no API key, no Data Safety disclosure. That decision also says
 * the home card's map preview becomes a static representation, and the reason is in
 * docs/00_CORE_RULES.md — a tile request is the parked coordinate leaving the device, to a
 * third party, every time the screen is drawn. Nothing here varies with the coordinate, so
 * nothing here can carry it: the same shapes are drawn for every parking.
 *
 * What makes that honest rather than decorative is the copy beside it. The label is
 * `마지막으로 확인된 위치` — never "the car's exact location" (docs/04_IOS_IMPLEMENTATION.md §9,
 * and underground accuracy is exactly when that claim would be a lie) — and the action
 * below it opens a real map, which is where a real position belongs.
 *
 * [pinLabel] is the floor, drawn beside the pin as in both mockups. It stays on-device.
 */
@Composable
fun StaticLocationArtwork(
    modifier: Modifier = Modifier,
    pinLabel: String? = null,
    pinSize: Dp = 28.dp,
) {
    val streets = MaterialTheme.colorScheme.surface
    val ground = MaterialTheme.colorScheme.surfaceVariant
    val park = MaterialTheme.colorScheme.tertiaryContainer

    Box(
        modifier = modifier.clip(MaterialTheme.shapes.large),
        contentAlignment = Alignment.Center,
    ) {
        // Decorative: the pin and the label beside it say nothing the caption does not.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { },
        ) {
            drawRect(ground)
            drawBlocks(streets, park)
        }

        Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(pinSize),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primary,
                content = {},
            )
            Icon(
                painter = painterResource(R.drawable.ic_car),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(pinSize * 0.55f),
            )
        }

        if (pinLabel != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(start = pinSize * 2.4f),
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    text = pinLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.spacing.small,
                        vertical = MaterialTheme.spacing.hairline,
                    ),
                )
            }
        }
    }
}

/**
 * Fixed proportions of the drawing surface — a block plan, not a place.
 *
 * Expressed as fractions so the same artwork reads at the home card's thumbnail size and
 * at the detail screen's full width.
 */
private fun DrawScope.drawBlocks(
    streets: Color,
    park: Color,
) {
    val w = size.width
    val h = size.height
    val road = h * 0.09f

    drawRect(park, topLeft = Offset(w * 0.72f, h * 0.60f), size = Size(w * 0.28f, h * 0.40f))
    drawRect(park, topLeft = Offset(0f, 0f), size = Size(w * 0.16f, h * 0.22f))

    drawRect(streets, topLeft = Offset(0f, h * 0.30f), size = Size(w, road))
    drawRect(streets, topLeft = Offset(0f, h * 0.72f), size = Size(w, road * 0.8f))
    drawRect(streets, topLeft = Offset(w * 0.24f, 0f), size = Size(road * 0.8f, h))
    drawRect(streets, topLeft = Offset(w * 0.68f, 0f), size = Size(road, h))
}

/** The detail screen's map block: the artwork, full width, at the mockup's proportions. */
@Composable
fun LocationPreviewCard(
    pinLabel: String?,
    caption: String,
    modifier: Modifier = Modifier,
) {
    ParkingkokCard(modifier = modifier, contentPadding = MaterialTheme.spacing.medium) {
        StaticLocationArtwork(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT),
            pinLabel = pinLabel,
            pinSize = 36.dp,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = MaterialTheme.spacing.tiny,
                top = MaterialTheme.spacing.small,
            ),
        )
    }
}

/** Matches the mockup's roughly 2:1 map block without pinning it to a device width. */
private val PREVIEW_HEIGHT = 168.dp
