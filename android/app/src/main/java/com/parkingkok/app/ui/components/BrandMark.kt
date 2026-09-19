package com.parkingkok.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.parkingkok.app.R

/**
 * A map pin: a disc with a point under it.
 *
 * The width is the disc's diameter; whatever height is left below it is the point. Every
 * proportion is a fraction of the width so the same shape reads at header size and at the
 * size of the pin dropped on the location artwork.
 */
val PinShape: Shape = GenericShape { size, _ ->
    val diameter = size.width
    val radius = diameter / 2f
    addOval(Rect(0f, 0f, diameter, diameter))
    // The point starts inside the disc, below its widest line, so the two read as one
    // silhouette rather than a ball balanced on a triangle.
    moveTo(radius - radius * TIP_HALF_WIDTH, radius + radius * TIP_START)
    lineTo(radius, size.height)
    lineTo(radius + radius * TIP_HALF_WIDTH, radius + radius * TIP_START)
    close()
}

/**
 * The 주차핀 mark: the app's own icon inside a pin, as in every mockup's header.
 *
 * A pin rather than the circle this used to be, because the circle said nothing — the pin
 * is the one shape the whole product is about, and it is what tells the header apart from
 * any other app's round logo.
 */
@Composable
fun BrandPin(modifier: Modifier = Modifier, width: Dp = 40.dp) {
    val height = width * PIN_ASPECT
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clearAndSetSemantics { },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
            shape = PinShape,
            color = MaterialTheme.colorScheme.primary,
            content = {},
        )
        Icon(
            painter = painterResource(R.drawable.ic_car),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.TopCenter)
                // Centred on the disc, not on the whole pin, which the point makes taller.
                .offset(y = width * ICON_INSET)
                .size(width * ICON_FRACTION),
        )
    }
}

/** Total height as a multiple of the disc diameter. */
private const val PIN_ASPECT = 1.3f

/** Half-width of the point where it leaves the disc, as a fraction of the radius. */
private const val TIP_HALF_WIDTH = 0.62f

/** How far down the radius the point starts. */
private const val TIP_START = 0.55f

/** Glyph size as a fraction of the disc diameter. */
private const val ICON_FRACTION = 0.52f

/** Top inset that centres the glyph on the disc. */
private const val ICON_INSET = (1f - ICON_FRACTION) / 2f
