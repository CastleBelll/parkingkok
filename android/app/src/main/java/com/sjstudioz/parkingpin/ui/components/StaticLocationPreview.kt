package com.sjstudioz.parkingpin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sjstudioz.parkingpin.theme.spacing

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
 * [pinLabel] is the floor and [zoneLabel] the zone, drawn on the block plan as in both
 * mockups. Both stay on-device.
 */
@Composable
fun StaticLocationArtwork(
    modifier: Modifier = Modifier,
    pinLabel: String? = null,
    zoneLabel: String? = null,
    pinSize: Dp = 28.dp,
) {
    val streets = MaterialTheme.colorScheme.surface
    val blocks = MaterialTheme.colorScheme.surfaceVariant
    val blockEdge = MaterialTheme.colorScheme.outlineVariant
    val park = MaterialTheme.colorScheme.tertiaryContainer
    val shape = MaterialTheme.shapes.large
    val blockRadius = with(LocalDensity.current) { BLOCK_RADIUS.toPx() }

    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        // Decorative: the pin and the labels on it say nothing the caption does not.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { },
        ) {
            drawRect(streets)
            drawBlocks(blocks, park, blockEdge, blockRadius)
        }

        BrandPin(modifier = Modifier.align(Alignment.Center), height = pinSize)

        if (pinLabel != null) {
            MapChip(
                text = pinLabel,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(start = pinSize * LABEL_OFFSET),
            )
        }

        if (zoneLabel != null) {
            MapChip(
                text = zoneLabel,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(MaterialTheme.spacing.small),
            )
        }
    }
}

/** A small label resting on the block plan, as in `01-home-main.png`. */
@Composable
private fun MapChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = containerColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.small,
                vertical = MaterialTheme.spacing.hairline,
            ),
        )
    }
}

/**
 * Fixed proportions of the drawing surface — a block plan, not a place.
 *
 * City blocks are drawn, and the page colour showing between them is what reads as the
 * streets. Drawing it the other way round — bands of white over a flat ground — is what
 * made the old version look like a chequerboard: the "roads" came out as wide as the
 * blocks and nothing had a shape.
 *
 * Expressed as fractions so the same artwork reads at the home card's thumbnail size and
 * at the detail screen's full width.
 */
private fun DrawScope.drawBlocks(
    blocks: Color,
    park: Color,
    edge: Color,
    cornerPx: Float,
) {
    val w = size.width
    val h = size.height
    // Narrow gaps: the streets are what shows between the blocks, and a street as wide as
    // a block is a chequerboard rather than a plan.
    val columns = listOf(-0.10f to 0.31f, 0.36f to 0.65f, 0.70f to 1.10f)
    val rows = listOf(-0.10f to 0.29f, 0.34f to 0.62f, 0.67f to 1.10f)
    // Two green blocks, on the diagonal, so the plan has a little colour without
    // pretending to describe a real place.
    val green = setOf(0 to 0, 2 to 2)

    columns.forEachIndexed { column, (left, right) ->
        rows.forEachIndexed { row, (top, bottom) ->
            val topLeft = Offset(w * left, h * top)
            val blockSize = Size(w * (right - left), h * (bottom - top))
            val corner = CornerRadius(cornerPx, cornerPx)
            drawRoundRect(
                color = if (column to row in green) park else blocks,
                topLeft = topLeft,
                size = blockSize,
                cornerRadius = corner,
            )
            // The tints in the palette sit a hair apart from white, so the blocks need an
            // edge to be blocks at thumbnail size rather than a faint smudge.
            drawRoundRect(
                color = edge,
                topLeft = topLeft,
                size = blockSize,
                cornerRadius = corner,
                style = Stroke(width = 1f),
            )
        }
    }
}

/** The detail screen's map block: the artwork, full width, at the mockup's proportions. */
@Composable
fun LocationPreviewCard(
    pinLabel: String?,
    zoneLabel: String?,
    caption: String,
    modifier: Modifier = Modifier,
) {
    ParkingpinCard(modifier = modifier, contentPadding = MaterialTheme.spacing.medium) {
        StaticLocationArtwork(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT),
            pinLabel = pinLabel,
            zoneLabel = zoneLabel,
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

/** City blocks are softened the same amount whatever size the artwork is drawn at. */
private val BLOCK_RADIUS = 4.dp

/** How far the floor label sits from the pin, as a multiple of the pin's width. */
private const val LABEL_OFFSET = 2.2f
